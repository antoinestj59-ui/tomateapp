-- =====================================================================================
-- TOMATE — 0005_media_reseau.sql
-- 1. Stories avec photo ou vidéo (le texte devient facultatif si un média est présent).
-- 2. Bucket `stories` : lecture publique, écriture strictement personnelle.
-- 3. Correctif bloquant : le créateur d'une conversation peut ajouter les membres initiaux.
-- Idempotent : réexécutable sans erreur sur une base déjà initialisée.
-- =====================================================================================

-- ------------------------------------------------------------------ 1. colonnes média
alter table public.stories add column if not exists media_path text;
alter table public.stories add column if not exists media_type text;

do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conname = 'stories_media_type_check'
           and conrelid = 'public.stories'::regclass
    ) then
        alter table public.stories
            add constraint stories_media_type_check
            check (media_type is null or media_type in ('image', 'video'));
    end if;
end $$;

-- Une story peut être une simple photo, une vidéo, ou un texte : au moins l'un des deux.
alter table public.stories drop constraint if exists stories_body_check;
alter table public.stories
    add constraint stories_body_check
    check (
        char_length(body) <= 300
        and (char_length(body) > 0 or media_path is not null)
    );

-- Le média d'une story doit rester dans le dossier de son auteur.
create or replace function public.story_media_is_own(author uuid, path text)
returns boolean
language sql
immutable
as $$
    select path is null or split_part(path, '/', 1) = author::text;
$$;

-- La policy d'insertion exige déjà author_id = auth.uid() : on y ajoute le contrôle du média.
drop policy if exists stories_insert_self on public.stories;
create policy stories_insert_self on public.stories
    for insert to authenticated
    with check (
        author_id = auth.uid()
        and public.story_media_is_own(author_id, media_path)
        and expires_at > now()
        and expires_at <= now() + interval '25 hours'
    );

-- La vue garde ses colonnes existantes et expose le média (CREATE OR REPLACE n'autorise
-- l'ajout qu'en fin de liste : media_path et media_type passent après viewed_by_me).
create or replace view public.stories_feed
with (security_invoker = true)
as
select
    s.id,
    s.author_id,
    pr.handle as author_handle,
    pr.display_name as author_display_name,
    s.body,
    s.background,
    s.created_at,
    s.expires_at,
    exists (
        select 1 from public.story_views v
         where v.story_id = s.id and v.viewer_id = auth.uid()
    ) as viewed_by_me,
    s.media_path,
    s.media_type
from public.stories s
join public.profiles pr on pr.id = s.author_id;

grant select on public.stories_feed to authenticated;

-- --------------------------------------------------------------------- 2. bucket
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'stories',
    'stories',
    true,
    26 * 1024 * 1024,
    array['image/jpeg', 'image/png', 'image/webp', 'video/mp4', 'video/webm', 'video/quicktime']
)
on conflict (id) do update
set public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists stories_bucket_read on storage.objects;
create policy stories_bucket_read on storage.objects
    for select to authenticated
    using (bucket_id = 'stories');

drop policy if exists stories_bucket_insert_own on storage.objects;
create policy stories_bucket_insert_own on storage.objects
    for insert to authenticated
    with check (
        bucket_id = 'stories'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists stories_bucket_delete_own on storage.objects;
create policy stories_bucket_delete_own on storage.objects
    for delete to authenticated
    using (
        bucket_id = 'stories'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

-- ------------------------------------------------- 3. correctif messagerie (bloquant)
-- Avant : on ne pouvait insérer un membre que si on était déjà membre. À la création d'une
-- conversation, personne n'est membre, donc l'insertion du créateur ET du destinataire
-- échouait en 403 : aucune conversation ne pouvait exister. Le créateur peut désormais
-- ajouter les membres initiaux ; ensuite la règle « membre déjà membre » s'applique.
drop policy if exists conversation_members_insert_member on public.conversation_members;
drop policy if exists conversation_members_insert_creator on public.conversation_members;
create policy conversation_members_insert_creator on public.conversation_members
    for insert to authenticated
    with check (
        public.is_conversation_member(conversation_id, auth.uid())
        or exists (
            select 1 from public.conversations c
             where c.id = conversation_id
               and c.created_by = auth.uid()
        )
    );
