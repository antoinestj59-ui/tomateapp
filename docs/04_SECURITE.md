# 04 — Sécurité, confidentialité et chiffrement

## 1. Résumé exécutable

| Question | Réponse honnête |
|---|---|
| Les messages sont-ils chiffrés de bout en bout ? | **Non.** TLS vers Supabase uniquement. L'UI l'indique partout. |
| Y a-t-il de la cryptographie maison ? | **Non.** Aucun algorithme implémenté à la main ; Android Keystore + AES-GCM uniquement. |
| Y a-t-il une clé secrète dans l'app ? | **Non.** Seule la clé publique `sb_publishable_…` est embarquée. |
| Les données sont-elles protégées côté serveur ? | **Oui, par RLS** : chaque table est verrouillée par défaut (`0002_rls.sql`). |
| Un relais peut-il lire les messages ? | **Non** : il transporte une enveloppe opaque et ne persiste rien. |

## 2. Modèle de menace

### Actifs protégés

1. Contenu des publications privées et des messages.
2. Identité de l'utilisateur (pseudo, bio, visibilité).
3. Jetons de session (access + refresh).
4. Code de récupération de compte.
5. Liste des comptes bloqués.

### Adversaires considérés

| Adversaire | Capacité | Mitigation |
|---|---|---|
| Autre utilisateur de l'app | appelle l'API avec son propre JWT | RLS : `auth.uid()` est la seule source d'identité ; aucun `author_id` client n'est cru |
| Utilisateur malveillant | modifie les requêtes, rejoue, usurpe un id | contraintes + `with check (author_id = auth.uid())` ; `client_id` unique pour les messages |
| Relais curieux | reçoit des enveloppes | payload opaque, aucune persistance, aucune journalisation de contenu |
| Appareil perdu/volé | accès au stockage local | session et code de récupération chiffrés par Keystore ; déconnexion efface le profil local |
| Observateur réseau local (BLE/Wi-Fi) | écoute les trames | contenu non chiffré de bout en bout ⇒ **risque assumé et documenté** ; ne pas envoyer de secret par proximité |
| Opérateur du serveur | accès base | hors modèle E2EE : les données sont lisibles côté serveur aujourd'hui |

### Hors périmètre (assumé)

- Sécurité physique de l'appareil.
- Attaques par canal auxiliaire.
- Compromission de l'Android Keystore.
- Fuites via sauvegardes système (les règles d'extraction excluent les données sensibles).

## 3. Secrets et identifiants

| Secret | Où il vit | Dans le dépôt ? | Dans l'APK ? |
|---|---|---|---|
| Clé publique Supabase | `BuildConfig` / configuration | oui (public par nature) | oui (usage prévu) |
| Clé `service_role` | jamais utilisée | **non** | **non** |
| Mot de passe Postgres | jamais utilisé | **non** | **non** |
| Access token | mémoire + DataStore | non | non |
| Refresh token | DataStore, **chiffré** (Keystore AES-GCM) | non | non |
| Code de récupération | Keystore, affiché une seule fois | non | non |
| Keystore de signature release | à créer par l'utilisateur, hors dépôt | non | — |

Aucune valeur secrète n'a été demandée dans le chat, ni écrite dans un fichier, ni
committée. La configuration Supabase est fournie par variables d'environnement au moment
du build (`local.properties` ou valeurs par défaut publiques).

## 4. Ce qui est chiffré localement

`KeystoreBox` : clé AES-256 générée dans Android Keystore (non exportable), chiffrement
AES/GCM/NoPadding, IV aléatoire par message, tag d'authentification vérifié au déchiffrement.

Utilisations :

1. **Refresh token** Supabase (le plus sensible : il permet de rejouer une session).
2. **Code de récupération** de compte.

Le contenu des messages et publications n'est **pas** chiffré au repos dans Room. Ce choix
est explicite : le chiffrement de la base locale exigerait SQLCipher (dépendance native) et
donnerait une fausse impression de sécurité sans E2EE. À traiter comme un chantier dédié.

## 5. E2EE : état réel et plan

### État

```kotlin
class E2eeNotConfigured : E2eeEngine {
    override val status = E2eeStatus.NotConfigured
    override suspend fun encrypt(...) = E2eeResult.Failed("Le chiffrement de bout en bout n'est pas encore opérationnel…")
    override suspend fun decrypt(...) = E2eeResult.Failed(...)
}
```

L'interface ne peut donc pas afficher par erreur un cadenas : `E2eeStatus.badgeLabel()`
renvoie « Non chiffré de bout en bout » tant que l'état n'est pas `Operational`.
Un chiffrement partiel (chiffrer sans gérer les prekeys, les sessions et les safety
numbers) serait **pire que l'absence** : il donnerait une garantie fausse.

### Candidat retenu pour un jalon dédié

| Candidat | Version | Pourquoi | Prérequis |
|---|---|---|---|
| `org.signal:libsignal-android` | 0.76.1 (Maven Central) | protocole Signal réel (X3DH + Double Ratchet), maintenu, audité | publication de prekeys, stockage de sessions, UI de safety numbers, composant serveur |
| MLS (RFC 9420) | — | adapté aux groupes | aucune implémentation Kotlin/Android maintenue vérifiée |

Un jalon E2EE complet doit inclure : génération et publication de prekeys, établissement de
session, chiffrement/déchiffrement, rotation, vérification hors bande (safety numbers),
tests d'interopérabilité, et migration des conversations existantes. Tant que ce jalon
n'est pas terminé **et vérifié**, l'app annonce l'absence.

## 6. RLS : principes appliqués

Fichier de référence : [`supabase/migrations/0002_rls.sql`](../supabase/migrations/0002_rls.sql).

1. **Refus par défaut** : RLS activée sur les 12 tables, aucune politique permissive
   globale.
2. **Identité serveur** : toutes les politiques comparent à `auth.uid()`. Aucun identifiant
   fourni par le client n'est accepté pour l'identité.
3. **Visibilité calculée côté serveur** : `Public`, `Followers`, `Community`, `Private`
   sont évaluées en base via des fonctions `stable security definer` au `search_path` figé.
4. **Blocage bidirectionnel** : `is_blocked_between(a, b)` empêche les deux sens.
5. **Écritures contraintes** : `with check` sur chaque insertion (auteur, propriétaire,
   membre actif, expéditeur de message).
6. **Vue sans contournement** : `feed` est créée avec `security_invoker = true`.
7. **Signalements** : insertion et lecture limitées au déclarant ; le traitement est
   réservé au tableau de bord (rôle service).
8. **Stockage** : bucket `avatars` public en lecture, écriture limitée à
   `<user_id>/…`.

Vérification : [`tooling/rls_smoke_test.sh`](../tooling/rls_smoke_test.sh) (7 assertions,
deux comptes anonymes réels). **Non exécuté ici** : les migrations doivent d'abord être
appliquées par l'utilisateur (option A).

## 7. Permissions Android

| Permission | Usage | Portée |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | synchronisation Supabase | normale |
| `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (API 31+) | proximité | runtime |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (≤ API 30) | proximité | installation |
| `NEARBY_WIFI_DEVICES` (API 33+) | NSD | runtime |
| `ACCESS_FINE_LOCATION` (`maxSdkVersion=30`) | découverte Wi-Fi legacy | runtime, **plafonnée** |
| `POST_NOTIFICATIONS` (API 33+) | états de synchronisation | runtime |
| `FOREGROUND_SERVICE` | synchronisation longue | normale |

Aucune permission de localisation en arrière-plan, aucun accès aux contacts, aux SMS, au
microphone ou à la caméra.

## 8. Bonnes pratiques appliquées au code

- Aucune journalisation de contenu de message, de jeton ou de code de récupération.
- Les erreurs affichées à l'utilisateur sont en français et ne contiennent pas de détail
  serveur exploitable.
- Les identifiants de message sont générés côté client pour l'idempotence, jamais réutilisés
  entre conversations.
- Les actions destructrices demandent confirmation.
- Les URLs et clés proviennent de la configuration de build, pas de chaînes dispersées.

## 9. À faire avant une publication Play Store

| Point | Criticité |
|---|---|
| E2EE réel **ou** communication claire (déjà le cas dans l'app) | haute |
| Suppression de compte côté serveur | **obligatoire** (politique Play) |
| Politique de confidentialité hébergée | **obligatoire** |
| Formulaire Data Safety exact | **obligatoire** |
| Chiffrement de la base locale (SQLCipher) | moyenne |
| Rotation des clés et révocation de session | moyenne |
| Audit externe de la RLS | haute |
| Tests de pénétration de l'API | haute |
