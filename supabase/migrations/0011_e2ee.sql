-- 0011 — clés publiques de chiffrement de bout en bout
--
-- Chaque compte publie deux clés publiques P-256 : une pour l'accord de clé (ECDH) et une pour
-- la signature (ECDSA). Les clés privées ne quittent jamais l'appareil.
--
-- Ce que le serveur peut voir : les clés publiques (par nature publiques) et le corps chiffré des
-- messages. Ce qu'il ne peut pas voir : le contenu des conversations 1-1.

alter table public.profiles
    add column if not exists e2ee_agree_key text
        check (e2ee_agree_key is null or char_length(e2ee_agree_key) between 80 and 120);

alter table public.profiles
    add column if not exists e2ee_sign_key text
        check (e2ee_sign_key is null or char_length(e2ee_sign_key) between 80 and 120);

-- Une clé publiée ne doit pas pouvoir être remplacée par quelqu'un d'autre : la politique
-- `profiles_update_self` de 0002 impose déjà `id = auth.uid()`, on la recrée ici explicitement
-- pour que cette migration reste lisible seule.
drop policy if exists profiles_update_self on public.profiles;
create policy profiles_update_self on public.profiles
    for update using (id = auth.uid()) with check (id = auth.uid());

comment on column public.profiles.e2ee_agree_key is
    'Clé publique ECDH P-256 (point non compressé, base64url). Publiée par le client.';
comment on column public.profiles.e2ee_sign_key is
    'Clé publique ECDSA P-256 (point non compressé, base64url). Sert à vérifier les messages chiffrés.';
