-- 0008 — Quitter ou supprimer une conversation
--
-- Constat : `conversation_members` autorisait déjà un membre à supprimer sa propre adhésion
-- (quitter), mais aucune politique n'autorisait la suppression d'une conversation. Résultat :
-- un créateur ne pouvait pas supprimer son groupe, et une conversation de test restait
-- définitivement en base, visible par ses membres, sans aucun moyen de la retirer.
--
-- Sémantique retenue :
--   * quitter : chaque membre supprime sa seule ligne `conversation_members` (déjà permis) ;
--   * supprimer pour tout le monde : seul le créateur peut supprimer la conversation, ce qui
--     supprime en cascade ses messages et ses adhésions (`on delete cascade`).
--
-- Aucun droit supplémentaire n'est accordé aux non-créateurs : la condition est vérifiée par
-- PostgreSQL à chaque suppression, pas par le client.

drop policy if exists conversations_delete_creator on public.conversations;
create policy conversations_delete_creator on public.conversations
    for delete to authenticated
    using (created_by = auth.uid());

-- Les messages sont déjà `on delete cascade` ; on le vérifie explicitement pour que la
-- suppression d'une conversation ne laisse jamais de messages orphelins.
do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conrelid = 'public.messages'::regclass
          and conname = 'messages_conversation_id_fkey'
          and confdeltype = 'c'
    ) then
        raise notice 'messages_conversation_id_fkey ne cascade pas : vérifier la migration 0001.';
    end if;
end $$;
