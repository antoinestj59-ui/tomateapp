-- =====================================================================================
-- TOMATE — 0009_medias_messagerie.sql
-- Photos, vidéos et messages vocaux dans les conversations.
--
-- Contrairement aux buckets `posts` et `stories`, qui sont publics parce qu'ils
-- alimentent le fil, le bucket `messages` est PRIVÉ : une pièce jointe n'est lisible
-- que par les membres de sa conversation. Les clients obtiennent une URL signée
-- temporaire (6 h) et aucun fichier n'est accessible sans session valide.
-- =====================================================================================

-- ------------------------------------------------ 1. colonnes de pièce jointe
alter table public.messages
    add column if not exists media_path text,
    add column if not exists media_kind text,
    add column if not exists media_mime text,
    add column if not exists media_size bigint;

comment on column public.messages.media_path is
    'Chemin dans le bucket privé messages : <conversation_id>/<expéditeur>/<fichier>.';

alter table public.messages drop constraint if exists messages_media_kind_check;
alter table public.messages add constraint messages_media_kind_check
    check (media_kind is null or media_kind in ('image', 'video', 'audio', 'file'));

-- Un chemin sans type, ou un type sans chemin, rendrait l'affichage ambigu.
alter table public.messages drop constraint if exists messages_media_coherent;
alter table public.messages add constraint messages_media_coherent
    check (
        (media_path is null and media_kind is null)
        or (media_path is not null and media_kind is not null)
    );

alter table public.messages drop constraint if exists messages_media_size_check;
alter table public.messages add constraint messages_media_size_check
    check (media_size is null or media_size between 1 and 26214400);

-- ------------------------------------------ 2. un message peut n'être qu'un média
alter table public.messages drop constraint if exists messages_body_check;
alter table public.messages add constraint messages_body_check
    check (
        char_length(body) <= 4000
        and (char_length(btrim(body)) > 0 or media_path is not null)
    );

-- ------------------------------------------------------------- 3. bucket privé
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'messages',
    'messages',
    false,
    25 * 1024 * 1024,
    array[
        'image/jpeg', 'image/png', 'image/webp',
        'video/mp4', 'video/webm', 'video/quicktime',
        'audio/mpeg', 'audio/mp4', 'audio/webm', 'audio/ogg', 'audio/aac'
    ]
)
on conflict (id) do update
set public = false,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

-- ---------------------- 4. conversion tolérante d'un segment de chemin en uuid
-- Un chemin malformé ne doit pas faire échouer la politique de stockage avec une
-- erreur 22P02 : il doit simplement ne rien autoriser.
create or replace function public.safe_uuid(value text)
returns uuid
language plpgsql
immutable
set search_path = pg_catalog, pg_temp
as $$
begin
    return value::uuid;
exception when others then
    return null;
end;
$$;

-- ------------------- 5. politiques : <conversation_id>/<expéditeur>/<fichier>
drop policy if exists messages_bucket_read on storage.objects;
create policy messages_bucket_read on storage.objects
    for select to authenticated
    using (
        bucket_id = 'messages'
        and public.is_conversation_member(
            public.safe_uuid((storage.foldername(name))[1]),
            auth.uid()
        )
    );

drop policy if exists messages_bucket_insert_member on storage.objects;
create policy messages_bucket_insert_member on storage.objects
    for insert to authenticated
    with check (
        bucket_id = 'messages'
        and (storage.foldername(name))[2] = auth.uid()::text
        and public.is_conversation_member(
            public.safe_uuid((storage.foldername(name))[1]),
            auth.uid()
        )
    );

-- Seul l'expéditeur supprime son fichier ; un fichier orphelin (message supprimé)
-- reste stocké : la suppression des objets est faite par le client qui retire le message.
drop policy if exists messages_bucket_delete_own on storage.objects;
create policy messages_bucket_delete_own on storage.objects
    for delete to authenticated
    using (
        bucket_id = 'messages'
        and (storage.foldername(name))[2] = auth.uid()::text
        and public.is_conversation_member(
            public.safe_uuid((storage.foldername(name))[1]),
            auth.uid()
        )
    );

-- ------------------------------------------------------------------ vérification
-- Attendus : 4 colonnes, 3 politiques, 1 bucket privé.
do $$
begin
    if not exists (
        select 1 from information_schema.columns
         where table_schema = 'public' and table_name = 'messages'
           and column_name in ('media_path', 'media_kind', 'media_mime', 'media_size')
         group by table_schema, table_name
        having count(*) = 4
    ) then
        raise exception '0009 : colonnes de pièce jointe manquantes sur public.messages';
    end if;
    if (select public from storage.buckets where id = 'messages') is not false then
        raise exception '0009 : le bucket messages doit être privé';
    end if;
end;
$$;
