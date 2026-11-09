-- =====================================================================================
-- TOMATE — 0004_stories.sql
-- Stories éphémères (24 h) : texte + fond, vues individuelles, RLS alignée sur les profils.
-- Idempotent : réexécutable sans erreur sur une base déjà initialisée.
-- =====================================================================================

create table if not exists public.stories (
    id         uuid primary key default gen_random_uuid(),
    author_id  uuid not null references public.profiles (id) on delete cascade,
    body       text not null check (char_length(body) between 1 and 300),
    background text not null default 'sunset'
               check (background in ('sunset', 'ocean', 'forest', 'night', 'plain')),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '24 hours')
);

create index if not exists stories_author_idx on public.stories (author_id, created_at desc);
create index if not exists stories_expires_idx on public.stories (expires_at);

create table if not exists public.story_views (
    story_id  uuid not null references public.stories (id) on delete cascade,
    viewer_id uuid not null references public.profiles (id) on delete cascade,
    viewed_at timestamptz not null default now(),
    primary key (story_id, viewer_id)
);

-- ---------------------------------------------------------------- fonctions d'appui
-- Un profil Public, ou un profil Followers que le lecteur suit : mêmes règles que profiles_select,
-- sans récursion de politique (SECURITY DEFINER + search_path figé).
create or replace function public.can_view_story_author(author uuid, reader uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (
        select 1 from public.profiles p
         where p.id = author
           and not public.is_blocked_between(reader, author)
           and (
               p.visibility = 'Public'
               or (p.visibility = 'Followers' and public.follows_author(author, reader))
               or (p.visibility = 'Community' and exists (
                       select 1 from public.community_members a
                       join public.community_members b on a.community_id = b.community_id
                        where a.user_id = reader and b.user_id = author
                          and a.status = 'Active' and b.status = 'Active'
                   ))
           )
    );
$$;

create or replace function public.can_view_story(target uuid, reader uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (
        select 1 from public.stories s
         where s.id = target
           and s.expires_at > now()
           and (s.author_id = reader or public.can_view_story_author(s.author_id, reader))
    );
$$;

-- ------------------------------------------------------------------------- privilèges
alter table public.stories     enable row level security;
alter table public.story_views enable row level security;

grant select, insert, delete on public.stories to authenticated;
grant select, insert on public.story_views to authenticated;

-- =====================================================================================
-- STORIES — lecture : non expirée, auteur non bloqué, profil visible.
-- =====================================================================================
drop policy if exists stories_select on public.stories;
create policy stories_select on public.stories
    for select to authenticated
    using (
        expires_at > now()
        and (
            author_id = auth.uid()
            or public.can_view_story_author(author_id, auth.uid())
        )
    );

drop policy if exists stories_insert_self on public.stories;
create policy stories_insert_self on public.stories
    for insert to authenticated
    with check (
        author_id = auth.uid()
        and expires_at > now()
        and expires_at <= now() + interval '25 hours'
    );

drop policy if exists stories_delete_author on public.stories;
create policy stories_delete_author on public.stories
    for delete to authenticated
    using (author_id = auth.uid());

-- =====================================================================================
-- VUES — un lecteur n'écrit que sa propre vue, sur une story qu'il peut voir.
-- L'auteur d'une story voit qui l'a regardée ; personne d'autre.
-- =====================================================================================
drop policy if exists story_views_select on public.story_views;
create policy story_views_select on public.story_views
    for select to authenticated
    using (
        viewer_id = auth.uid()
        or exists (
            select 1 from public.stories s
             where s.id = story_id and s.author_id = auth.uid()
        )
    );

drop policy if exists story_views_insert_self on public.story_views;
create policy story_views_insert_self on public.story_views
    for insert to authenticated
    with check (
        viewer_id = auth.uid()
        and public.can_view_story(story_id, auth.uid())
    );

-- =====================================================================================
-- Vue d'agrégation : story + auteur + vue par moi. `security_invoker` pour que les
-- politiques de `stories` et `profiles` s'appliquent au lecteur, jamais au propriétaire.
-- =====================================================================================
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
    ) as viewed_by_me
from public.stories s
join public.profiles pr on pr.id = s.author_id;

grant select on public.stories_feed to authenticated;
