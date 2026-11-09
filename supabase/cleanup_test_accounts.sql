-- Nettoyage des comptes de test créés pendant le développement.
--
-- Ces comptes ne sont pas des personnes : ils ont été créés par les vérifications réelles
-- (médias, RLS, notifications, sélecteur de contacts). Ils apparaissent dans la recherche et dans
-- le sélecteur de contacts de tous les utilisateurs, donc ils doivent être supprimés avant une
-- publication réelle.
--
-- À exécuter dans le SQL Editor du tableau de bord Supabase, connecté au propriétaire du projet
-- (les politiques RLS interdisent la suppression d'un autre compte depuis l'application).
--
-- 1. VÉRIFIER d'abord la liste ci-dessous. Ne supprimez jamais un compte qui pourrait être un
--    vrai utilisateur : les pseudos personnels (`antoine`, `jules`, `verifmedia`) sont volontairement
--    exclus du motif.
-- 2. Exécuter ensuite le bloc DELETE. La suppression dans `auth.users` supprime en cascade le
--    profil, les publications, les messages et les adhésions (`on delete cascade`, migration 0001).

-- ---------------------------------------------------------------------------------------------
-- 1. Aperçu (lecture seule)
-- ---------------------------------------------------------------------------------------------
select u.id, p.handle, p.display_name, u.is_anonymous, u.created_at
from auth.users u
join public.profiles p on p.id = u.id
where p.handle in (
    'buffy_web_test', 'chain_m2', 'tomate_peer', 'ttt', 'web_qr1',
    'web_story1', 'web_story2', 'web_ui9',
    'story_a', 'story_b', 'story_c',
    'grp1_7138', 'grp2_7138'
)
   or p.handle ~ '^(diag|diag2|diag3|diag4)_[0-9]+$'
   or p.handle ~ '^rls_[ab]_[0-9]+$'
   or p.handle ~ '^web_e2e[0-9]*$'
   or p.handle ~ '^pick_[0-9]+$'
   or p.handle ~ '^tomate_(smoke|probe|temp)_[0-9]+$'
order by u.created_at;

-- ---------------------------------------------------------------------------------------------
-- 2. Suppression (décommenter après vérification de l'aperçu ci-dessus)
-- ---------------------------------------------------------------------------------------------
-- delete from auth.users u
-- using public.profiles p
-- where p.id = u.id
--   and (
--       p.handle in (
--           'buffy_web_test', 'chain_m2', 'tomate_peer', 'ttt', 'web_qr1',
--           'web_story1', 'web_story2', 'web_ui9',
--           'story_a', 'story_b', 'story_c',
--           'grp1_7138', 'grp2_7138'
--       )
--       or p.handle ~ '^(diag|diag2|diag3|diag4)_[0-9]+$'
--       or p.handle ~ '^rls_[ab]_[0-9]+$'
--       or p.handle ~ '^web_e2e[0-9]*$'
--       or p.handle ~ '^pick_[0-9]+$'
--       or p.handle ~ '^tomate_(smoke|probe|temp)_[0-9]+$'
--   );

-- ---------------------------------------------------------------------------------------------
-- 3. Conversations de test restées sans membres actifs
--    (après la migration 0008, un créateur peut supprimer les siennes)
-- ---------------------------------------------------------------------------------------------
-- delete from public.conversations c
-- where c.title in ('Contact Picker')
--   and not exists (
--       select 1 from public.conversation_members m where m.conversation_id = c.id
--   );
