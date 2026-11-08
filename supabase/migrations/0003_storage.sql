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
