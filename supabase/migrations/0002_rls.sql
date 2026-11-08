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
