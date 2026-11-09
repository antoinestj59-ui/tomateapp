-- =====================================================================================
-- TOMATE — 0006_social.sql
-- 1. Publications avec photo ou vidéo (bucket `posts`, contrôle du dossier auteur).
-- 2. Fil étendu : média, avatar de l'auteur, nom et slug de la communauté.
-- 3. Communautés : membres visibles pour un groupe public, adhésion honnête
--    (public = actif, privé = demande en attente) — ferme l'auto-adhésion en privé.
-- Idempotent : réexécutable sans erreur sur une base déjà initialisée.
-- =====================================================================================

-- ------------------------------------------------------ 1. publications avec média
alter table public.posts add column if not exists media_path text;
alter table public.posts add column if not exists media_type text;

do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conname = 'posts_media_type_check'
           and conrelid = 'public.posts'::regclass
    ) then
        alter table public.posts
            add constraint posts_media_type_check
            check (media_type is null or media_type in ('image', 'video'));
    end if;
end $$;

-- Le texte devient facultatif dès qu'un média est présent, mais l'un des deux est exigé.
alter table public.posts drop constraint if exists posts_body_check;
alter table public.posts
    add constraint posts_body_check
    check (
        char_length(body) <= 2000
        and (char_length(btrim(body)) > 0 or media_path is not null)
    );

-- Le média d'une publication doit rester dans le dossier de son auteur.
create or replace function public.post_media_is_own(author uuid, path text)
returns boolean
language sql
immutable
as $$
    select path is null or split_part(path, '/', 1) = author::text;
$$;

drop policy if exists posts_insert_member on public.posts;
create policy posts_insert_member on public.posts
    for insert to authenticated
    with check (
        author_id = auth.uid()
        and deleted_at is null
        and public.post_media_is_own(author_id, media_path)
        and (
            community_id is null
            or public.is_community_member(community_id, auth.uid())
        )
    );

-- ---------------------------------------------------------------- 2. fil étendu
-- CREATE OR REPLACE n'ajoute des colonnes qu'en fin de liste : les quatre nouvelles
-- colonnes passent après reacted_by_me.
create or replace view public.feed
with (security_invoker = true)
as
select
    p.id,
    p.author_id,
    pr.handle as author_handle,
    pr.display_name as author_display_name,
    p.body,
    p.community_id,
    p.visibility,
    p.created_at,
    (select count(*) from public.post_reactions r where r.post_id = p.id)::int as reaction_count,
    (select count(*) from public.post_comments c
      where c.post_id = p.id and c.deleted_at is null)::int as comment_count,
    exists (select 1 from public.post_reactions r2
             where r2.post_id = p.id and r2.user_id = auth.uid()) as reacted_by_me,
    p.media_path,
    p.media_type,
    pr.avatar_path as author_avatar_path,
    c.name as community_name,
    c.slug as community_slug
from public.posts p
join public.profiles pr on pr.id = p.author_id
left join public.communities c on c.id = p.community_id
where p.deleted_at is null;

grant select on public.feed to authenticated;

-- ------------------------------------------------------------------- 3. bucket
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'posts',
    'posts',
    true,
    26 * 1024 * 1024,
    array['image/jpeg', 'image/png', 'image/webp', 'video/mp4', 'video/webm', 'video/quicktime']
)
on conflict (id) do update
set public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists posts_bucket_read on storage.objects;
create policy posts_bucket_read on storage.objects
    for select to authenticated
    using (bucket_id = 'posts');

drop policy if exists posts_bucket_insert_own on storage.objects;
create policy posts_bucket_insert_own on storage.objects
    for insert to authenticated
    with check (
        bucket_id = 'posts'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists posts_bucket_delete_own on storage.objects;
create policy posts_bucket_delete_own on storage.objects
    for delete to authenticated
    using (
        bucket_id = 'posts'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

-- ---------------------------------------------------- 4. communautés : adhésion
-- SECURITY DEFINER + search_path figé : la politique de `community_members` a besoin de
-- connaître la visibilité sans récursion dans `communities`.
create or replace function public.community_is_public(cid uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (
        select 1 from public.communities c
         where c.id = cid and c.visibility = 'Public'
    );
$$;

-- La liste des membres d'un groupe public est visible : elle sert à découvrir le groupe.
-- Un groupe privé garde sa liste réservée à ses membres.
drop policy if exists community_members_select on public.community_members;
create policy community_members_select on public.community_members
    for select to authenticated
    using (
        user_id = auth.uid()
        or public.is_community_member(community_id, auth.uid())
        or public.community_is_public(community_id)
    );

-- Adhésion honnête : public → membre actif ; privé → demande en attente, jamais active.
-- Ferme la faille où n'importe qui s'ajoutait « Active » dans un groupe privé.
drop policy if exists community_members_insert_self on public.community_members;
create policy community_members_insert_self on public.community_members
    for insert to authenticated
    with check (
        user_id = auth.uid()
        and role = 'Member'
        and (
            (public.community_is_public(community_id) and status = 'Active')
            or (not public.community_is_public(community_id) and status = 'Pending')
        )
    );
