-- TOMATE — 0010_message_reactions.sql
-- Réactions aux messages (emoji), sans nouvelle dépendance.
-- À coller dans Supabase > SQL Editor > New query, puis Run (une seule fois).
-- Sans cette migration, l'application affiche « Réactions non activées » au lieu d'échouer
-- silencieusement : aucune donnée n'est inventée.

-- 1. Table : un emoji par compte et par message.
create table if not exists public.message_reactions (
    message_id uuid not null references public.messages (id) on delete cascade,
    user_id uuid not null references public.profiles (id) on delete cascade,
    emoji text not null check (char_length(emoji) between 1 and 12),
    created_at timestamptz not null default now(),
    primary key (message_id, user_id, emoji)
);

alter table public.message_reactions enable row level security;

grant select, insert, delete on public.message_reactions to authenticated;

-- 2. Lecture : seuls les membres de la conversation voient les réactions.
drop policy if exists message_reactions_select_member on public.message_reactions;
create policy message_reactions_select_member on public.message_reactions
    for select to authenticated
    using (
        exists (
            select 1 from public.messages m
            join public.conversation_members cm on cm.conversation_id = m.conversation_id
            where m.id = message_reactions.message_id
              and cm.user_id = (select auth.uid())
        )
    );

-- 3. Ajout : l'auteur de la réaction est le compte connecté, membre de la conversation.
drop policy if exists message_reactions_insert_member on public.message_reactions;
create policy message_reactions_insert_member on public.message_reactions
    for insert to authenticated
    with check (
        user_id = (select auth.uid())
        and exists (
            select 1 from public.messages m
            join public.conversation_members cm on cm.conversation_id = m.conversation_id
            where m.id = message_reactions.message_id
              and cm.user_id = (select auth.uid())
        )
    );

-- 4. Retrait : uniquement sa propre réaction.
drop policy if exists message_reactions_delete_own on public.message_reactions;
create policy message_reactions_delete_own on public.message_reactions
    for delete to authenticated
    using (user_id = (select auth.uid()));
