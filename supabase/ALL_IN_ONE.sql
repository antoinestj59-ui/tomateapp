-- =====================================================================================
-- TOMATE — ALL_IN_ONE.sql
-- Fichier unique à coller dans le SQL Editor Supabase (option A).
-- Contenu identique à 0001_init.sql + 0002_rls.sql + 0003_storage.sql + 0004_stories.sql
-- + 0005_media_reseau.sql + 0006_social.sql, dans cet ordre.
-- À exécuter en UNE seule requête : sélectionnez TOUT ce fichier, puis Run.
-- =====================================================================================

-- =====================================================================================
-- TOMATE — 0001_init.sql
-- Schéma initial : profils, réseau social, communautés, messagerie, modération.
-- À appliquer dans le SQL Editor Supabase (voir docs/05_SUPABASE_SETUP.md).
-- Idempotent : réexécutable sans erreur sur une base déjà initialisée.
-- =====================================================================================

create extension if not exists pgcrypto;

-- --------------------------------------------------------------------------- profils
create table if not exists public.profiles (
    id            uuid primary key references auth.users (id) on delete cascade,
    handle        text not null check (handle ~ '^[a-z0-9_]{3,20}$'),
    display_name  text not null check (char_length(display_name) between 1 and 40),
    bio           text not null default '' check (char_length(bio) <= 280),
    avatar_path   text check (avatar_path is null or char_length(avatar_path) <= 200),
    visibility    text not null default 'Public'
                  check (visibility in ('Public', 'Followers', 'Community', 'Private')),
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create unique index if not exists profiles_handle_lower_idx on public.profiles (lower(handle));

-- ------------------------------------------------------------------------ communautés
create table if not exists public.communities (
    id           uuid primary key default gen_random_uuid(),
    name         text not null check (char_length(name) between 3 and 60),
    slug         text not null check (slug ~ '^[a-z0-9-]{3,40}$'),
    description  text not null default '' check (char_length(description) <= 500),
    owner_id     uuid not null references public.profiles (id) on delete cascade,
    visibility   text not null default 'Public' check (visibility in ('Public', 'Private')),
    member_count integer not null default 0 check (member_count >= 0),
    created_at   timestamptz not null default now()
);

create unique index if not exists communities_slug_idx on public.communities (slug);

create table if not exists public.community_members (
    community_id uuid not null references public.communities (id) on delete cascade,
    user_id      uuid not null references public.profiles (id) on delete cascade,
    role         text not null default 'Member' check (role in ('Owner', 'Moderator', 'Member')),
    status       text not null default 'Active' check (status in ('Active', 'Pending', 'Rejected', 'Banned')),
    created_at   timestamptz not null default now(),
    primary key (community_id, user_id)
);

create index if not exists community_members_user_idx on public.community_members (user_id);

-- ---------------------------------------------------------------------- publications
create table if not exists public.posts (
    id           uuid primary key default gen_random_uuid(),
    author_id    uuid not null references public.profiles (id) on delete cascade,
    body         text not null check (char_length(body) between 1 and 2000),
    community_id uuid references public.communities (id) on delete set null,
    visibility   text not null default 'Public'
                 check (visibility in ('Public', 'Followers', 'Community', 'Private')),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz
);

create index if not exists posts_created_idx on public.posts (created_at desc);
create index if not exists posts_author_idx on public.posts (author_id);
create index if not exists posts_community_idx on public.posts (community_id);

create table if not exists public.post_comments (
    id         uuid primary key default gen_random_uuid(),
    post_id    uuid not null references public.posts (id) on delete cascade,
    author_id  uuid not null references public.profiles (id) on delete cascade,
    body       text not null check (char_length(body) between 1 and 1000),
    parent_id  uuid references public.post_comments (id) on delete cascade,
    created_at timestamptz not null default now(),
    deleted_at timestamptz
);

create index if not exists post_comments_post_idx on public.post_comments (post_id, created_at);

create table if not exists public.post_reactions (
    post_id    uuid not null references public.posts (id) on delete cascade,
    user_id    uuid not null references public.profiles (id) on delete cascade,
    kind       text not null default 'tomate' check (char_length(kind) between 1 and 20),
    created_at timestamptz not null default now(),
    primary key (post_id, user_id, kind)
);

-- ------------------------------------------------------------------------- relations
create table if not exists public.follows (
    follower_id uuid not null references public.profiles (id) on delete cascade,
    followee_id uuid not null references public.profiles (id) on delete cascade,
    created_at  timestamptz not null default now(),
    primary key (follower_id, followee_id),
    constraint follows_no_self check (follower_id <> followee_id)
);

create table if not exists public.blocks (
    blocker_id uuid not null references public.profiles (id) on delete cascade,
    blocked_id uuid not null references public.profiles (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (blocker_id, blocked_id),
    constraint blocks_no_self check (blocker_id <> blocked_id)
);

-- ----------------------------------------------------------------------------- reçus
create table if not exists public.reports (
    id          uuid primary key default gen_random_uuid(),
    reporter_id uuid not null references public.profiles (id) on delete cascade,
    target_type text not null check (target_type in ('User', 'Post', 'Comment', 'Community', 'Message')),
    target_id   uuid not null,
    reason      text not null check (reason in ('Spam', 'Harassment', 'Hate', 'Violence', 'Sexual', 'Misinformation', 'Other')),
    details     text not null default '' check (char_length(details) <= 1000),
    status      text not null default 'Open' check (status in ('Open', 'Reviewing', 'Actioned', 'Dismissed')),
    created_at  timestamptz not null default now()
);

create index if not exists reports_status_idx on public.reports (status, created_at);

-- ------------------------------------------------------------------------ messagerie
create table if not exists public.conversations (
    id              uuid primary key default gen_random_uuid(),
    title           text not null default '' check (char_length(title) <= 60),
    is_group        boolean not null default false,
    created_by      uuid not null references public.profiles (id) on delete cascade,
    created_at      timestamptz not null default now(),
    last_message_at timestamptz not null default now()
);

create table if not exists public.conversation_members (
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    user_id         uuid not null references public.profiles (id) on delete cascade,
    created_at      timestamptz not null default now(),
    primary key (conversation_id, user_id)
);

create index if not exists conversation_members_user_idx on public.conversation_members (user_id);

create table if not exists public.messages (
    id              uuid primary key default gen_random_uuid(),
    client_id       uuid not null,
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    sender_id       uuid not null references public.profiles (id) on delete cascade,
    body            text not null check (char_length(body) between 1 and 4000),
    encrypted       boolean not null default false,
    reply_to_id     uuid references public.messages (id) on delete set null,
    created_at      timestamptz not null default now(),
    unique (conversation_id, client_id)
);

create index if not exists messages_conversation_idx on public.messages (conversation_id, created_at);

-- =====================================================================================
-- Fonctions utilitaires (SECURITY DEFINER limité, search_path figé)
-- =====================================================================================

create or replace function public.tomate_touch_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists profiles_touch on public.profiles;
create trigger profiles_touch before update on public.profiles
    for each row execute function public.tomate_touch_updated_at();

drop trigger if exists posts_touch on public.posts;
create trigger posts_touch before update on public.posts
    for each row execute function public.tomate_touch_updated_at();

-- Le créateur d'une communauté en devient automatiquement propriétaire et membre actif.
create or replace function public.tomate_community_owner_membership()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    insert into public.community_members (community_id, user_id, role, status)
    values (new.id, new.owner_id, 'Owner', 'Active')
    on conflict (community_id, user_id) do update set role = 'Owner', status = 'Active';
    update public.communities c
       set member_count = (select count(*) from public.community_members m
                            where m.community_id = new.id and m.status = 'Active')
     where c.id = new.id;
    return new;
end;
$$;

drop trigger if exists communities_owner_membership on public.communities;
create trigger communities_owner_membership after insert on public.communities
    for each row execute function public.tomate_community_owner_membership();

-- Le créateur d'une conversation en devient membre.
create or replace function public.tomate_conversation_creator_membership()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    insert into public.conversation_members (conversation_id, user_id)
    values (new.id, new.created_by)
    on conflict do nothing;
    return new;
end;
$$;

drop trigger if exists conversations_creator_membership on public.conversations;
create trigger conversations_creator_membership after insert on public.conversations
    for each row execute function public.tomate_conversation_creator_membership();

-- Compteur de membres maintenu à jour.
create or replace function public.tomate_refresh_member_count()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    target uuid;
begin
    target := coalesce(new.community_id, old.community_id);
    update public.communities c
       set member_count = (select count(*) from public.community_members m
                            where m.community_id = target and m.status = 'Active')
     where c.id = target;
    return null;
end;
$$;

drop trigger if exists community_members_count on public.community_members;
create trigger community_members_count after insert or update or delete on public.community_members
    for each row execute function public.tomate_refresh_member_count();

-- Horodatage de la dernière activité d'une conversation.
create or replace function public.tomate_touch_conversation()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    update public.conversations c
       set last_message_at = new.created_at
     where c.id = new.conversation_id;
    return new;
end;
$$;

drop trigger if exists messages_touch_conversation on public.messages;
create trigger messages_touch_conversation after insert on public.messages
    for each row execute function public.tomate_touch_conversation();

-- =====================================================================================
-- Vues
-- =====================================================================================

-- Fil agrégé. `security_invoker` est indispensable : la vue doit respecter les RLS de
-- l'utilisateur courant, jamais celles du propriétaire de la vue.
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
             where r2.post_id = p.id and r2.user_id = auth.uid()) as reacted_by_me
from public.posts p
join public.profiles pr on pr.id = p.author_id
where p.deleted_at is null;

grant select on public.feed to authenticated;


-- =====================================================================================
-- TOMATE — 0002_rls.sql
-- Row Level Security : chaque table exposée par l'API est verrouillée par défaut.
-- Principe : aucune confiance dans le client. L'identité est TOUJOURS auth.uid().
-- =====================================================================================

-- Active RLS partout (idempotent).
alter table public.profiles            enable row level security;
alter table public.posts               enable row level security;
alter table public.post_comments       enable row level security;
alter table public.post_reactions      enable row level security;
alter table public.follows             enable row level security;
alter table public.blocks              enable row level security;
alter table public.reports             enable row level security;
alter table public.communities         enable row level security;
alter table public.community_members   enable row level security;
alter table public.conversations       enable row level security;
alter table public.conversation_members enable row level security;
alter table public.messages            enable row level security;

-- Privilèges de base (RLS filtre ensuite ligne par ligne).
grant select, insert, update, delete on public.profiles to authenticated;
grant select, insert, update, delete on public.posts to authenticated;
grant select, insert, update, delete on public.post_comments to authenticated;
grant select, insert, delete on public.post_reactions to authenticated;
grant select, insert, delete on public.follows to authenticated;
grant select, insert, delete on public.blocks to authenticated;
grant select, insert on public.reports to authenticated;
grant select, insert, update, delete on public.communities to authenticated;
grant select, insert, update, delete on public.community_members to authenticated;
grant select, insert, update on public.conversations to authenticated;
grant select, insert, delete on public.conversation_members to authenticated;
grant select, insert on public.messages to authenticated;

-- ------------------------------------------------------------------ fonctions d'appui
create or replace function public.is_blocked_between(a uuid, b uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (
        select 1 from public.blocks bl
         where (bl.blocker_id = a and bl.blocked_id = b)
            or (bl.blocker_id = b and bl.blocked_id = a)
    );
$$;

create or replace function public.is_community_member(cid uuid, uid uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (
        select 1 from public.community_members m
         where m.community_id = cid and m.user_id = uid and m.status = 'Active'
    );
$$;

create or replace function public.community_role_of(cid uuid, uid uuid)
returns text
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select m.role from public.community_members m
     where m.community_id = cid and m.user_id = uid and m.status = 'Active'
     limit 1;
$$;

create or replace function public.is_conversation_member(cid uuid, uid uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (
        select 1 from public.conversation_members cm
         where cm.conversation_id = cid and cm.user_id = uid
    );
$$;

create or replace function public.follows_author(author uuid, reader uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (
        select 1 from public.follows f
         where f.follower_id = reader and f.followee_id = author
    );
$$;

-- =====================================================================================
-- PROFILS
-- =====================================================================================
drop policy if exists profiles_select on public.profiles;
create policy profiles_select on public.profiles
    for select to authenticated
    using (
        id = auth.uid()
        or (
            not public.is_blocked_between(auth.uid(), id)
            and case visibility
                    when 'Public' then true
                    when 'Followers' then public.follows_author(id, auth.uid())
                    when 'Community' then exists (
                        select 1 from public.community_members a
                        join public.community_members b on a.community_id = b.community_id
                        where a.user_id = auth.uid() and b.user_id = profiles.id
                          and a.status = 'Active' and b.status = 'Active'
                    )
                    else false
                end
        )
    );

drop policy if exists profiles_insert_self on public.profiles;
create policy profiles_insert_self on public.profiles
    for insert to authenticated
    with check (id = auth.uid());

drop policy if exists profiles_update_self on public.profiles;
create policy profiles_update_self on public.profiles
    for update to authenticated
    using (id = auth.uid())
    with check (id = auth.uid());

drop policy if exists profiles_delete_self on public.profiles;
create policy profiles_delete_self on public.profiles
    for delete to authenticated
    using (id = auth.uid());

-- =====================================================================================
-- PUBLICATIONS
-- =====================================================================================
drop policy if exists posts_select on public.posts;
create policy posts_select on public.posts
    for select to authenticated
    using (
        deleted_at is null
        and not public.is_blocked_between(auth.uid(), author_id)
        and (
            author_id = auth.uid()
            or (visibility = 'Public')
            or (visibility = 'Followers' and public.follows_author(author_id, auth.uid()))
            or (visibility = 'Community' and community_id is not null
                and public.is_community_member(community_id, auth.uid()))
        )
    );

drop policy if exists posts_insert_member on public.posts;
create policy posts_insert_member on public.posts
    for insert to authenticated
    with check (
        author_id = auth.uid()
        and deleted_at is null
        and (
            community_id is null
            or public.is_community_member(community_id, auth.uid())
        )
    );

drop policy if exists posts_update_author on public.posts;
create policy posts_update_author on public.posts
    for update to authenticated
    using (author_id = auth.uid())
    with check (author_id = auth.uid());

drop policy if exists posts_delete_author on public.posts;
create policy posts_delete_author on public.posts
    for delete to authenticated
    using (author_id = auth.uid());

-- =====================================================================================
-- COMMENTAIRES
-- =====================================================================================
drop policy if exists comments_select on public.post_comments;
create policy comments_select on public.post_comments
    for select to authenticated
    using (
        deleted_at is null
        and not public.is_blocked_between(auth.uid(), author_id)
        and exists (select 1 from public.posts p where p.id = post_id)
    );

drop policy if exists comments_insert on public.post_comments;
create policy comments_insert on public.post_comments
    for insert to authenticated
    with check (
        author_id = auth.uid()
        and exists (select 1 from public.posts p where p.id = post_id)
    );

drop policy if exists comments_update_author on public.post_comments;
create policy comments_update_author on public.post_comments
    for update to authenticated
    using (author_id = auth.uid())
    with check (author_id = auth.uid());

drop policy if exists comments_delete_author on public.post_comments;
create policy comments_delete_author on public.post_comments
    for delete to authenticated
    using (author_id = auth.uid());

-- =====================================================================================
-- RÉACTIONS
-- =====================================================================================
drop policy if exists reactions_select on public.post_reactions;
create policy reactions_select on public.post_reactions
    for select to authenticated
    using (true);

drop policy if exists reactions_insert_self on public.post_reactions;
create policy reactions_insert_self on public.post_reactions
    for insert to authenticated
    with check (
        user_id = auth.uid()
        and exists (select 1 from public.posts p where p.id = post_id)
    );

drop policy if exists reactions_delete_self on public.post_reactions;
create policy reactions_delete_self on public.post_reactions
    for delete to authenticated
    using (user_id = auth.uid());

-- =====================================================================================
-- ABONNEMENTS / BLOCAGES
-- =====================================================================================
drop policy if exists follows_select on public.follows;
create policy follows_select on public.follows
    for select to authenticated
    using (follower_id = auth.uid() or followee_id = auth.uid());

drop policy if exists follows_insert_self on public.follows;
create policy follows_insert_self on public.follows
    for insert to authenticated
    with check (follower_id = auth.uid());

drop policy if exists follows_delete_self on public.follows;
create policy follows_delete_self on public.follows
    for delete to authenticated
    using (follower_id = auth.uid());

drop policy if exists blocks_select_self on public.blocks;
create policy blocks_select_self on public.blocks
    for select to authenticated
    using (blocker_id = auth.uid());

drop policy if exists blocks_insert_self on public.blocks;
create policy blocks_insert_self on public.blocks
    for insert to authenticated
    with check (blocker_id = auth.uid());

drop policy if exists blocks_delete_self on public.blocks;
create policy blocks_delete_self on public.blocks
    for delete to authenticated
    using (blocker_id = auth.uid());

-- =====================================================================================
-- SIGNALEMENTS (ajout seul par l'auteur, lecture par l'auteur uniquement)
-- =====================================================================================
drop policy if exists reports_insert_self on public.reports;
create policy reports_insert_self on public.reports
    for insert to authenticated
    with check (reporter_id = auth.uid());

drop policy if exists reports_select_self on public.reports;
create policy reports_select_self on public.reports
    for select to authenticated
    using (reporter_id = auth.uid());

-- =====================================================================================
-- COMMUNAUTÉS
-- =====================================================================================
drop policy if exists communities_select on public.communities;
create policy communities_select on public.communities
    for select to authenticated
    using (
        visibility = 'Public'
        or public.is_community_member(id, auth.uid())
    );

drop policy if exists communities_insert_self on public.communities;
create policy communities_insert_self on public.communities
    for insert to authenticated
    with check (owner_id = auth.uid());

drop policy if exists communities_update_owner on public.communities;
create policy communities_update_owner on public.communities
    for update to authenticated
    using (owner_id = auth.uid())
    with check (owner_id = auth.uid());

drop policy if exists communities_delete_owner on public.communities;
create policy communities_delete_owner on public.communities
    for delete to authenticated
    using (owner_id = auth.uid());

drop policy if exists community_members_select on public.community_members;
create policy community_members_select on public.community_members
    for select to authenticated
    using (
        user_id = auth.uid()
        or public.is_community_member(community_id, auth.uid())
    );

-- Un utilisateur ne peut s'ajouter que lui-même, et uniquement en Member.
drop policy if exists community_members_insert_self on public.community_members;
create policy community_members_insert_self on public.community_members
    for insert to authenticated
    with check (
        user_id = auth.uid()
        and role = 'Member'
        and status in ('Active', 'Pending')
    );

-- Un modérateur/propriétaire peut changer les rôles et statuts des autres membres.
drop policy if exists community_members_update_staff on public.community_members;
create policy community_members_update_staff on public.community_members
    for update to authenticated
    using (
        public.community_role_of(community_id, auth.uid()) in ('Owner', 'Moderator')
        or user_id = auth.uid()
    )
    with check (
        public.community_role_of(community_id, auth.uid()) in ('Owner', 'Moderator')
        or user_id = auth.uid()
    );

drop policy if exists community_members_delete on public.community_members;
create policy community_members_delete on public.community_members
    for delete to authenticated
    using (
        user_id = auth.uid()
        or public.community_role_of(community_id, auth.uid()) in ('Owner', 'Moderator')
    );

-- =====================================================================================
-- CONVERSATIONS ET MESSAGES
-- =====================================================================================
drop policy if exists conversations_select_member on public.conversations;
create policy conversations_select_member on public.conversations
    for select to authenticated
    using (public.is_conversation_member(id, auth.uid()));

drop policy if exists conversations_insert_self on public.conversations;
create policy conversations_insert_self on public.conversations
    for insert to authenticated
    with check (created_by = auth.uid());

drop policy if exists conversations_update_member on public.conversations;
create policy conversations_update_member on public.conversations
    for update to authenticated
    using (public.is_conversation_member(id, auth.uid()))
    with check (public.is_conversation_member(id, auth.uid()));

drop policy if exists conversation_members_select on public.conversation_members;
create policy conversation_members_select on public.conversation_members
    for select to authenticated
    using (public.is_conversation_member(conversation_id, auth.uid()));

drop policy if exists conversation_members_insert_member on public.conversation_members;
create policy conversation_members_insert_member on public.conversation_members
    for insert to authenticated
    with check (public.is_conversation_member(conversation_id, auth.uid()));

drop policy if exists conversation_members_delete_self on public.conversation_members;
create policy conversation_members_delete_self on public.conversation_members
    for delete to authenticated
    using (user_id = auth.uid());

drop policy if exists messages_select_member on public.messages;
create policy messages_select_member on public.messages
    for select to authenticated
    using (public.is_conversation_member(conversation_id, auth.uid()));

drop policy if exists messages_insert_member on public.messages;
create policy messages_insert_member on public.messages
    for insert to authenticated
    with check (
        sender_id = auth.uid()
        and public.is_conversation_member(conversation_id, auth.uid())
    );


-- =====================================================================================
-- TOMATE — 0003_storage.sql
-- Stockage des avatars : bucket public en lecture, écriture strictement personnelle.
-- L'arborescence est <user_id>/<fichier> : les politiques vérifient le premier segment.
-- =====================================================================================

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'avatars',
    'avatars',
    true,
    2 * 1024 * 1024,
    array['image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do update
set public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

-- Lecture publique : un avatar est visible par tout utilisateur authentifié.
drop policy if exists avatars_public_read on storage.objects;
create policy avatars_public_read on storage.objects
    for select to authenticated
    using (bucket_id = 'avatars');

-- Écriture : uniquement dans son propre dossier.
drop policy if exists avatars_insert_own on storage.objects;
create policy avatars_insert_own on storage.objects
    for insert to authenticated
    with check (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists avatars_update_own on storage.objects;
create policy avatars_update_own on storage.objects
    for update to authenticated
    using (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    )
    with check (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists avatars_delete_own on storage.objects;
create policy avatars_delete_own on storage.objects
    for delete to authenticated
    using (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

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

-- =====================================================================================
-- 0005_media_reseau.sql
-- =====================================================================================

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

-- =====================================================================================
-- 0006_social.sql
-- =====================================================================================

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
