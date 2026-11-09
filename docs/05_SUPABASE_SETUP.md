# 05 — Configuration Supabase (option A : SQL Editor)

TOMATE utilise un projet Supabase public, avec **authentification anonyme** et **RLS
complète**. Aucune clé secrète (`service_role`, mot de passe Postgres) n'est nécessaire,
ni dans l'APK, ni dans le dépôt, ni dans ce document.

## 0. État déjà vérifié

| Élément | Valeur | Vérifié |
|---|---|---|
| URL du projet | `https://omsupwmlncuapikbjumg.supabase.co` | oui (health 200) |
| Clé publique (publishable) | `sb_publishable_IOga6ANcbYMivphwt4mR7Q_VziRyrIj` | oui |
| Connexions anonymes | **activées** | oui (`/auth/v1/settings` → `anonymous_users: true`) |
| Inscription anonyme | fonctionnelle | oui (`is_anonymous: true`, JWT 3600 s) |
| Tables | **aucune** | oui — d'où `PGRST205` affiché par l'app avant migrations |

L'application est conçue pour rester utilisable **avant** les migrations et **sans
connexion** : un compte peut être créé hors ligne (identité locale), toutes les écritures
restent sur l'appareil, et le compte est relié à Supabase automatiquement dès qu'une
connexion et les migrations sont disponibles. Aucune donnée simulée n'est affichée.

## 1. Appliquer les migrations (5 minutes)

> ⚠️ **Erreur fréquente** : `syntax error at or near "supabase"` signifie qu'un **chemin de
> fichier** a été collé dans le SQL Editor (`supabase/migrations/0003_storage.sql`) au lieu
> de son **contenu**. Le SQL Editor n'exécute que du SQL : le chemin n'en est pas.

Deux façons de procéder, au choix.

### A. Le plus simple : un seul fichier

Ouvrir [`supabase/ALL_IN_ONE.sql`](../supabase/ALL_IN_ONE.sql), cliquer sur **Raw** (ou
« Copier le fichier brut »), **tout** sélectionner, puis **Run** une seule fois. Ce fichier
contient les quatre migrations dans le bon ordre, séparées par des lignes de commentaire.

### B. Fichier par fichier

Dans le tableau de bord Supabase : **SQL Editor → New query**, puis exécuter **dans cet
ordre**, en collant l'intégralité du contenu de chaque fichier (jamais son chemin) :

1. [`supabase/migrations/0001_init.sql`](../supabase/migrations/0001_init.sql)
   — tables, contraintes, index, déclencheurs, vue `feed`.
2. [`supabase/migrations/0002_rls.sql`](../supabase/migrations/0002_rls.sql)
   — activation de RLS + politiques complètes + fonctions d'appui.
3. [`supabase/migrations/0003_storage.sql`](../supabase/migrations/0003_storage.sql)
   — bucket d'avatars et politiques de stockage.
4. [`supabase/migrations/0004_stories.sql`](../supabase/migrations/0004_stories.sql)
   — stories de 24 h : tables `stories` / `story_views`, politiques RLS, vue `stories_feed`.

Dans l'éditeur GitHub, le bouton **Copier** en haut à droite du fichier copie le contenu
exact ; c'est ce contenu qu'il faut coller.

Chaque fichier est idempotent (`create table if not exists`, `drop policy if exists`) :
les réexécuter ne casse rien.

## 2. Vérifier que les migrations sont bien appliquées

Toujours dans le SQL Editor :

```sql
-- 12 tables attendues
select table_name
from information_schema.tables
where table_schema = 'public'
order by table_name;

-- RLS active partout (aucune ligne ne doit renvoyer false)
select tablename, rowsecurity
from pg_tables
where schemaname = 'public';

-- nombre de politiques par table
select tablename, count(*) as policies
from pg_policies
where schemaname = 'public'
group by tablename
order by tablename;
```

Attendu : `profiles, communities, community_members, posts, post_comments,
post_reactions, follows, blocks, reports, conversations, conversation_members, messages`,
`rowsecurity = true` pour les 12, et au moins une politique par table.

## 3. Vérifier la vue `feed`

```sql
select * from public.feed limit 1;
```

La requête doit réussir (0 ligne au départ). La vue est créée avec
`security_invoker = true` : elle **hérite des RLS** de l'utilisateur courant. Sans cette
option, la vue contournerait les politiques — c'est un piège classique, volontairement
évité.

## 4. Tester la RLS avec deux comptes réels

Le script [`tooling/rls_smoke_test.sh`](../tooling/rls_smoke_test.sh) crée deux comptes
anonymes avec la clé publique et vérifie les règles suivantes :

| Test | Attendu |
|---|---|
| A crée son profil, A le relit | 200 |
| B lit le profil public de A | 200 (visibilité `Public`) |
| B tente de modifier le profil de A | **0 ligne affectée** |
| B tente de lire une publication `Private` de A | **0 ligne** |
| B tente d'insérer une publication avec `author_id = A` | **403 (RLS)** |
| B tente de lire les messages d'une conversation de A | **0 ligne** |
| A bloque B, B lit alors le profil de A | **0 ligne** |

Exécution (nécessite `curl` et `jq`, aucune clé secrète) :

```bash
./tooling/rls_smoke_test.sh
```

Le script imprime un rapport `PASS/FAIL` par assertion. **Tant que les migrations ne sont
pas appliquées, tous les tests échouent avec `PGRST205` — c'est normal et attendu.**

> Note : ces deux comptes anonymes restent dans `auth.users`. Ils peuvent être supprimés
> depuis **Authentication → Users** une fois les tests terminés.

## 5. Ce que l'application attend exactement

L'app n'utilise que des endpoints documentés, via un client HTTP minimal (ADR-002) :

| Usage | Requête |
|---|---|
| Session anonyme | `POST /auth/v1/signup` (corps vide) |
| Rafraîchissement | `POST /auth/v1/token?grant_type=refresh_token` |
| Déconnexion | `POST /auth/v1/logout` |
| Profil | `GET/POST/PATCH /rest/v1/profiles` |
| Fil | `GET /rest/v1/feed?select=*&order=created_at.desc&limit=…` |
| Publications | `POST /rest/v1/posts`, `PATCH /rest/v1/posts?id=eq.…` |
| Commentaires | `/rest/v1/post_comments` |
| Réactions | `/rest/v1/post_reactions` (clé primaire composite) |
| Communautés | `/rest/v1/communities`, `/rest/v1/community_members` |
| Messagerie | `/rest/v1/conversations`, `/rest/v1/messages` (déduplication `client_id`) |
| Blocages | `/rest/v1/blocks` |
| Signalements | `/rest/v1/reports` |
| Avatars | `/storage/v1/object/avatars/…` |

En-têtes envoyés : `apikey` (clé publique) et `Authorization: Bearer <JWT de session>`.
Le JWT est rafraîchi une fois sur `401`, puis l'erreur est remontée honnêtement.

## 6. Configuration de l'authentification anonyme

**Authentication → Providers → Anonymous sign-ins → Enable**. Déjà fait et vérifié.

Points importants :

- Un compte anonyme est un vrai utilisateur `auth.users` avec `is_anonymous = true`.
- Il est perdu si l'application est désinstallée sans code de récupération.
  L'UI l'annonce explicitement dans l'onboarding.
- La **restauration sur un autre appareil n'est pas disponible** : elle exigerait une
  fonction serveur (Edge Function) qui n'est pas déployée. L'écran Profil l'indique.

## 7. Ce qui reste volontairement manuel

| Action | Pourquoi |
|---|---|
| Appliquer les 3 fichiers SQL | Option A : l'utilisateur garde le contrôle du schéma |
| Créer le bucket `avatars` | Le fichier SQL le crée ; vérifier ensuite dans Storage |
| Activer la protection contre les mots de passe compromis | Réglage du tableau de bord |
| Configurer les limites de débit (Rate limits) | Réglage du tableau de bord |
| Sauvegardes / PITR | Offre Supabase, hors code |

## 8. Dépannage

| Symptôme | Cause | Solution |
|---|---|---|
| `PGRST205` dans l'app | tables absentes | appliquer `0001` et `0002` |
| `PGRST301` / 401 en boucle | JWT expiré et refresh refusé | se déconnecter puis recréer un compte anonyme |
| 403 à l'insertion d'une publication | `author_id` ≠ `auth.uid()` | comportement attendu de la RLS |
| 0 ligne sur `/rest/v1/feed` | aucune publication visible pour cet utilisateur | créer une publication publique |
| `new row violates check constraint "profiles_handle_check"` | pseudo hors format | 3–20 caractères `[a-z0-9_]`, non réservé |
| Erreur de stockage 403 | politiques du bucket | réexécuter `0003_storage.sql` |

## Migration 0005 — médias de stories et correctif messagerie

`supabase/migrations/0005_media_reseau.sql` :

1. colonnes `media_path` / `media_type` sur `stories` (photo ou vidéo, texte facultatif) ;
2. bucket `stories` (26 Mo, images et vidéos), lecture publique, écriture limitée à
   `<user_id>/…` ;
3. vue `stories_feed` étendue aux deux nouvelles colonnes ;
4. politique d'insertion des membres de conversation élargie au créateur (en plus du trigger
   qui ajoute déjà le créateur comme membre).

À exécuter dans le SQL Editor, puis vérifier :

```bash
curl -s "$SUPABASE_URL/rest/v1/stories_feed?select=id,media_path&limit=1" \
  -H "apikey: $KEY" -H "Authorization: Bearer $TOKEN"
```
