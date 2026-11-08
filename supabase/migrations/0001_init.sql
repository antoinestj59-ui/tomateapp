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
