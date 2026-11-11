# 04 — Sécurité, confidentialité et chiffrement

## 1. Résumé exécutable

| Question | Réponse honnête |
|---|---|
| Les messages sont-ils chiffrés de bout en bout ? | **Oui pour les conversations 1-1, non pour les groupes.** Le serveur ne stocke que du chiffré pour un échange à deux ; l'UI l'affiche message par message. |
| Y a-t-il de la cryptographie maison ? | **Aucun algorithme inventé.** P-256, HKDF-SHA256, AES-256-GCM et ECDSA viennent de la JCA Android et de WebCrypto. Seul HKDF est écrit à la main (API 33+ seulement), et il est vérifié contre le vecteur officiel du RFC 5869. |
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

## 5. E2EE : état réel (0.5.0)

### Ce qui est fait

| Brique | Détail | Vérification |
|---|---|---|
| Identité | Deux paires P-256 par appareil : **accord** (ECDH) et **signature** (ECDSA) | `EcKeys` + tests |
| Dérivation | ECDH → HKDF-SHA256 (sel = SHA-256 de la clé éphémère, info `tomate-e2ee-v1`) → clé AES-256 | vecteur RFC 5869 + interop |
| Message | Clé éphémère par message (ECIES), AES-256-GCM, IV 12 octets, tag 16 octets, AAD = `info|conversation|émetteur|epk` | 10 tests Kotlin |
| Authenticité | Signature ECDSA P-256 sur `aad|iv|ct`, vérifiée avant déchiffrement | test + HTTP réel |
| Interopérabilité | Android et PWA produisent et lisent le **même** format | `E2eeInteropTest` lance Node sur le vrai `web/js/e2ee.js` |
| Clés au repos | Clés privées chiffrées par le Keystore Android (`CryptoBox`), jamais envoyées | test « les clés privées ne sont pas écrites en clair » |
| Annuaire | `profiles.e2ee_agree_key` / `e2ee_sign_key` (migration `0011`) | script `verify_050_e2ee.sh` |
| Appairage | Lien unique `tomate://pair` avec les deux clés publiques → QR (ZXing), NFC (NDEF), collage | 4 tests Robolectric |
| Clé épinglée | Une clé reçue par appairage ne peut pas être remplacée par l'annuaire | test dédié |
| Numéro de sécurité | Empreinte des quatre clés, identique des deux côtés | test Kotlin + Node |
| Affichage | Chaque message porte « 🔒 chiffré » ou « non chiffré » ; un message illisible est dit tel quel | code + tests |

Format transporté dans `messages.body` :

```
[[tomate:e2ee:v1]]<from>|<epk>|<iv>|<signature>|<ciphertext>     (base64url sans remplissage)
```

### Modèle de menace, sans embellissement

| Menace | Protégé ? |
|---|---|
| Lecture par l'opérateur du serveur / une sauvegarde de base | **Oui** pour les messages 1-1 : le corps stocké est le chiffré. |
| Modification d'un message en base | **Oui** : la signature ECDSA de l'émetteur ne valide plus. |
| Usurpation d'émetteur | **Partiellement** : il faut la clé de l'émetteur, obtenue par appairage (vérifiée) ou par l'annuaire (non vérifiée). |
| Substitution de clé par un serveur malveillant sur un contact **appairé** | **Non** : la clé épinglée gagne toujours. |
| Substitution de clé sur un contact **non appairé** | **Oui, possible** : c'est pourquoi l'UI écrit « clé de l'annuaire, à vérifier » et affiche le numéro de sécurité. |
| Compromission ultérieure de la clé privée du destinataire | **Non protégé** : pas de Double Ratchet, donc pas de confidentialité persistante. |
| Groupes | **Non chiffrés** : aucune implémentation MLS retenue ; l'UI l'écrit. |
| Multi-appareil | **Un appareil = une clé.** Se connecter sur un autre appareil rend les anciens messages illisibles. |
| Analyse de trafic côté serveur (qui parle à qui, quand) | **Non protégé** : les métadonnées restent visibles. |

### Ce qui reste à faire, dans l'ordre

1. **Appliquer la migration `0011`** puis relancer `tooling/verify_050_e2ee.sh` (5 contrôles d'annuaire en attente).
2. Double Ratchet (libsignal) pour la confidentialité persistante — gros chantier, à ne pas simuler.
3. Chiffrement de groupe (MLS) ou, au minimum, une clé de groupe distribuée par message.
4. Clés PWA : aujourd'hui dans `localStorage` (lisibles par un script de la page) ; passer à des
   `CryptoKey` non exportables stockées en IndexedDB.
5. Test matériel : deux téléphones à portée pour le BLE, une étiquette NFC réelle, un scan caméra.

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
