-- =====================================================================================
-- TOMATE — 0007_compte_notifications.sql
-- 1. Notifications persistantes (messages, réactions, commentaires, demandes d'adhésion).
-- 2. Abonnements Web Push (PWA) et jetons FCM (Android), strictement personnels.
-- 3. Déclencheurs serveur : une notification est créée par le serveur, jamais par le client.
-- Idempotent : réexécutable sans erreur sur une base déjà initialisée.
-- =====================================================================================

-- ------------------------------------------------------------------ 1. notifications
create table if not exists public.notifications (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null references public.profiles (id) on delete cascade,
    actor_id        uuid references public.profiles (id) on delete set null,
    kind            text not null
                    check (kind in ('message', 'reaction', 'comment', 'join', 'story', 'system')),
    entity_id       uuid,
    conversation_id uuid references public.conversations (id) on delete cascade,
    body            text not null default '' check (char_length(body) <= 280),
    read_at         timestamptz,
    created_at      timestamptz not null default now()
);

create index if not exists notifications_user_idx
    on public.notifications (user_id, created_at desc);
create index if not exists notifications_unread_idx
    on public.notifications (user_id) where read_at is null;

alter table public.notifications enable row level security;

drop policy if exists notifications_select_own on public.notifications;
create policy notifications_select_own on public.notifications
    for select to authenticated
    using (user_id = auth.uid());

drop policy if exists notifications_update_own on public.notifications;
create policy notifications_update_own on public.notifications
    for update to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

drop policy if exists notifications_delete_own on public.notifications;
create policy notifications_delete_own on public.notifications
    for delete to authenticated
    using (user_id = auth.uid());

-- Aucune policy d'insertion : seuls les déclencheurs (security definer) écrivent.
grant select, update, delete on public.notifications to authenticated;

-- ------------------------------------------------------------- 2. déclencheurs

-- Nouveau message : prévient les autres membres de la conversation.
create or replace function public.notify_message()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    insert into public.notifications (user_id, actor_id, kind, entity_id, conversation_id, body)
    select cm.user_id, new.sender_id, 'message', new.id, new.conversation_id, left(new.body, 280)
      from public.conversation_members cm
     where cm.conversation_id = new.conversation_id
       and cm.user_id <> new.sender_id;
    return new;
end;
$$;

drop trigger if exists messages_notify on public.messages;
create trigger messages_notify
    after insert on public.messages
    for each row execute function public.notify_message();

-- Réaction : prévient l'auteur de la publication (jamais soi-même).
create or replace function public.notify_reaction()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    insert into public.notifications (user_id, actor_id, kind, entity_id, body)
    select p.author_id, new.user_id, 'reaction', p.id, 'a réagi à votre publication'
      from public.posts p
     where p.id = new.post_id
       and p.author_id <> new.user_id;
    return new;
end;
$$;

drop trigger if exists post_reactions_notify on public.post_reactions;
create trigger post_reactions_notify
    after insert on public.post_reactions
    for each row execute function public.notify_reaction();

-- Commentaire : prévient l'auteur de la publication (jamais soi-même).
create or replace function public.notify_comment()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    insert into public.notifications (user_id, actor_id, kind, entity_id, body)
    select p.author_id, new.author_id, 'comment', p.id, left(new.body, 280)
      from public.posts p
     where p.id = new.post_id
       and p.author_id <> new.author_id;
    return new;
end;
$$;

drop trigger if exists post_comments_notify on public.post_comments;
create trigger post_comments_notify
    after insert on public.post_comments
    for each row execute function public.notify_comment();

-- Demande d'adhésion : prévient le propriétaire du groupe privé.
create or replace function public.notify_join_request()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    if new.status = 'Pending' then
        insert into public.notifications (user_id, actor_id, kind, entity_id, body)
        select c.owner_id, new.user_id, 'join', c.id, 'demande à rejoindre votre groupe'
          from public.communities c
         where c.id = new.community_id
           and c.owner_id <> new.user_id;
    end if;
    return new;
end;
$$;

drop trigger if exists community_members_notify_request on public.community_members;
create trigger community_members_notify_request
    after insert on public.community_members
    for each row execute function public.notify_join_request();

-- Demande acceptée : prévient la personne concernée.
create or replace function public.notify_join_accepted()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    if new.status = 'Active' and old.status is distinct from 'Active' then
        insert into public.notifications (user_id, kind, entity_id, body)
        select new.user_id, 'join', c.id, 'votre demande a été acceptée : ' || c.name
          from public.communities c
         where c.id = new.community_id;
    end if;
    return new;
end;
$$;

drop trigger if exists community_members_notify_accepted on public.community_members;
create trigger community_members_notify_accepted
    after update of status on public.community_members
    for each row execute function public.notify_join_accepted();

-- ------------------------------------------------- 3. Web Push (PWA) et FCM (Android)

create table if not exists public.push_subscriptions (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references public.profiles (id) on delete cascade,
    endpoint     text not null unique,
    p256dh       text not null,
    auth_key     text not null,
    user_agent   text not null default '',
    created_at   timestamptz not null default now(),
    last_seen_at timestamptz not null default now()
);

create index if not exists push_subscriptions_user_idx on public.push_subscriptions (user_id);

alter table public.push_subscriptions enable row level security;

drop policy if exists push_subscriptions_own on public.push_subscriptions;
create policy push_subscriptions_own on public.push_subscriptions
    for all to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

grant select, insert, update, delete on public.push_subscriptions to authenticated;

create table if not exists public.device_tokens (
    user_id    uuid not null references public.profiles (id) on delete cascade,
    token      text not null check (char_length(token) between 16 and 4096),
    platform   text not null default 'android' check (platform in ('android', 'web')),
    updated_at timestamptz not null default now(),
    primary key (user_id, token)
);

alter table public.device_tokens enable row level security;

drop policy if exists device_tokens_own on public.device_tokens;
create policy device_tokens_own on public.device_tokens
    for all to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

grant select, insert, update, delete on public.device_tokens to authenticated;
