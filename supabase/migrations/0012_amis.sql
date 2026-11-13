-- =====================================================================================
-- 0012 — DEMANDES D'AMIS
--
-- Une demande est une ligne réelle, pas un compteur : `Pending` tant que le destinataire
-- n'a pas répondu, puis `Accepted` ou `Refused`. L'amitié est la ligne `Accepted`, dans
-- un sens ou dans l'autre (voir `public.are_friends`).
--
-- Règles :
--   * seul l'expéditeur crée sa propre demande ;
--   * seul le destinataire peut répondre (aucun client ne peut accepter à sa place) ;
--   * les deux peuvent supprimer la ligne (annuler / retirer) ;
--   * une demande vers quelqu'un qui t'a bloqué est refusée par la politique d'insertion.
-- =====================================================================================

create table if not exists public.friend_requests (
    id           uuid primary key default gen_random_uuid(),
    from_id      uuid not null references public.profiles (id) on delete cascade,
    to_id        uuid not null references public.profiles (id) on delete cascade,
    note         text not null default '' check (char_length(note) <= 280),
    status       text not null default 'Pending' check (status in ('Pending', 'Accepted', 'Refused')),
    created_at   timestamptz not null default now(),
    responded_at timestamptz,
    constraint friend_requests_not_self check (from_id <> to_id),
    constraint friend_requests_pair unique (from_id, to_id)
);

create index if not exists friend_requests_to_idx on public.friend_requests (to_id, status);
create index if not exists friend_requests_from_idx on public.friend_requests (from_id, status);

grant select, insert, update, delete on public.friend_requests to authenticated;

-- ------------------------------------------------------------------ amitié réciproque
create or replace function public.are_friends(a uuid, b uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (
        select 1
          from public.friend_requests r
         where r.status = 'Accepted'
           and ((r.from_id = a and r.to_id = b) or (r.from_id = b and r.to_id = a))
    );
$$;

-- ---------------------------------------------------------------------------- policies
drop policy if exists friend_requests_select on public.friend_requests;
create policy friend_requests_select on public.friend_requests
    for select to authenticated
    using (from_id = auth.uid() or to_id = auth.uid());

drop policy if exists friend_requests_insert_self on public.friend_requests;
create policy friend_requests_insert_self on public.friend_requests
    for insert to authenticated
    with check (
        from_id = auth.uid()
        and status = 'Pending'
        and not public.is_blocked_between(from_id, to_id)
    );

-- Le destinataire seul répond.
-- `from_id`/`to_id` sont figés par un trigger : une politique RLS qui relirait la même
-- table provoquerait une récursion infinie (« infinite recursion detected in policy »).
drop policy if exists friend_requests_update_recipient on public.friend_requests;
create policy friend_requests_update_recipient on public.friend_requests
    for update to authenticated
    using (to_id = auth.uid())
    with check (to_id = auth.uid() and status in ('Accepted', 'Refused'));

create or replace function public.friend_requests_guard()
returns trigger
language plpgsql
as $$
begin
    new.from_id := old.from_id;
    new.to_id := old.to_id;
    new.created_at := old.created_at;
    return new;
end;
$$;

drop trigger if exists friend_requests_guard on public.friend_requests;
create trigger friend_requests_guard
    before update on public.friend_requests
    for each row execute function public.friend_requests_guard();

drop policy if exists friend_requests_delete_party on public.friend_requests;
create policy friend_requests_delete_party on public.friend_requests
    for delete to authenticated
    using (from_id = auth.uid() or to_id = auth.uid());

-- ----------------------------------------------------------------- notifications : kinds
-- La contrainte d'origine n'autorisait pas les kinds d'amitié : on la remplace sans
-- supposer son nom exact (elle est retrouvée par sa définition).
do $$
declare
    c record;
begin
    for c in
        select conname
          from pg_constraint
         where conrelid = 'public.notifications'::regclass
           and contype = 'c'
           and pg_get_constraintdef(oid) ilike '%story%'
    loop
        execute format('alter table public.notifications drop constraint %I', c.conname);
    end loop;
end $$;

alter table public.notifications
    add constraint notifications_kind_check check (
        kind in ('message', 'reaction', 'comment', 'join', 'story', 'system', 'friend_request', 'friend_accept')
    );

-- ------------------------------------------------ une notification à la création
create or replace function public.notify_friend_request()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    actor text;
    suffix text;
begin
    if new.status <> 'Pending' then
        return new;
    end if;
    select coalesce(nullif(p.display_name, ''), p.handle)
      into actor
      from public.profiles p
     where p.id = new.from_id;

    suffix := case
        when new.note <> '' then ' : « ' || new.note || ' »'
        else ''
    end;

    insert into public.notifications (user_id, actor_id, kind, entity_id, body)
    values (
        new.to_id,
        new.from_id,
        'friend_request',
        new.id,
        coalesce(actor, 'Quelqu''un') || ' veut t''ajouter en ami' || suffix
    );
    return new;
end;
$$;

drop trigger if exists friend_requests_notify on public.friend_requests;
create trigger friend_requests_notify
    after insert on public.friend_requests
    for each row execute function public.notify_friend_request();

-- ------------------------------------------------ une notification à l'acceptation
create or replace function public.notify_friend_accept()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    actor text;
begin
    if new.status = 'Accepted' and old.status = 'Pending' then
        select coalesce(nullif(p.display_name, ''), p.handle)
          into actor
          from public.profiles p
         where p.id = new.to_id;

        insert into public.notifications (user_id, actor_id, kind, entity_id, body)
        values (
            new.from_id,
            new.to_id,
            'friend_accept',
            new.id,
            coalesce(actor, 'Ton contact') || ' a accepté ta demande d''ami'
        );
    end if;
    return new;
end;
$$;

drop trigger if exists friend_requests_notify_accept on public.friend_requests;
create trigger friend_requests_notify_accept
    after update of status on public.friend_requests
    for each row execute function public.notify_friend_accept();
