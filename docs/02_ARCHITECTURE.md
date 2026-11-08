# 02 — Architecture technique

## 1. Vue d'ensemble

```
┌──────────────────────────────────────────────────────────────┐
│ :app — Compose Material 3, navigation, ViewModels             │
│  onboarding · fil · publication · communautés · messages      │
│  chat · proximité · profil · réglages                         │
└───────────────┬──────────────────────────────┬───────────────┘
                │                              │
┌───────────────▼──────────────┐  ┌────────────▼───────────────┐
│ :data — source de vérité     │  │ :net — transports locaux   │
│  Room (12 entités)           │  │  BLE (GATT serveur/client) │
│  Supabase (HTTP Ktor)        │  │  Wi-Fi local (NSD + TCP)   │
│  SyncEngine (outbox)         │  │  RelayEngine + codec       │
│  Session, Keystore, réglages │  │  (aucune dépendance :data) │
└───────────────┬──────────────┘  └────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────┐
│ :core — modèles de domaine, Outcome/AppError, design system   │
└──────────────────────────────────────────────────────────────┘
```

Règles de dépendance : `:app → :data → :core`, `:app → :net → :core`, `:data ↛ :net`.
Aucune dépendance circulaire. `:net` ne connaît ni Room ni Supabase : il ne manipule que
des `MessageEnvelope` issus de `:core`.

## 2. Flux de données

### Lecture

```
Room (Flow) → Repository → ViewModel (StateFlow) → Compose
```

L'UI n'attend jamais le réseau pour afficher : elle observe Room. Une synchronisation
réussie met à jour Room, ce qui rafraîchit l'écran.

### Écriture (local-first)

```
Action UI → Repository
   ├─ écriture Room immédiate (visible instantanément)
   ├─ insertion d'une opération dans `outbox` (idempotente)
   └─ SyncEngine.drain()
         ├─ succès → opération supprimée, entité marquée synchronisée
         └─ échec   → opération conservée, backoff exponentiel avec jitter
```

`SyncEngine` est idempotent : chaque opération porte un identifiant stable ; les messages
portent un `client_id` unique contraint côté serveur
(`unique (conversation_id, client_id)`), ce qui rend un renvoi inoffensif.

### Conflits

| Conflit | Résolution |
|---|---|
| Message envoyé deux fois | `client_id` unique côté serveur → 409 absorbé, un seul message |
| Publication modifiée sur deux appareils | dernière écriture serveur gagne ; Room est remplacé par la réponse |
| Suppression locale non synchronisée | `deleted = 1` local, suppression envoyée, entité conservée jusqu'au succès |
| Profil modifié hors ligne | `pendingSync = 1`, PATCH rejoué au retour du réseau |

## 3. Modèle local (Room v1)

12 entités : `profiles`, `posts`, `comments`, `communities`, `community_members_cache`,
`conversations`, `messages`, `outbox`, `sync_state`, `blocked_users`, plus les tables
d'appui de réactions et de réglages. Points notables :

- Clés primaires = identifiants serveur (UUID) pour éviter toute réconciliation d'ID.
- `pendingSync` sur chaque entité modifiable localement.
- `outbox` ordonnée par date de création, avec `attempts` et `nextAttemptAt`.
- Les migrations de schéma sont versionnées ; les schémas exportés servent aux tests.

## 4. Réseau Supabase

### ADR-002 — client HTTP minimal au lieu de `supabase-kt`

**Contexte.** Deux options : `io.github.jan-tennert.supabase:supabase-kt` (client officiel
de la communauté, Kotlin 2.x, Ktor 3.x) ou un client HTTP écrit à la main.

**Décision.** Client Ktor minimal (`SupabaseApi`) couvrant `/auth/v1`, `/rest/v1` et
`/storage/v1`.

**Raisons.**

1. Les endpoints utilisés sont documentés et stables ; le besoin est petit (12 tables).
2. Chaque requête est auditable et testable avec `MockEngine` sans Android.
3. Aucune API « devinée » : les DTO sont explicites et versionnés dans le dépôt.
4. Les erreurs PostgREST sont mappées finement (`PGRST205` → `SchemaMissing`), ce qui
   permet un message utilisateur précis quand les migrations manquent.
5. Moins de surface de dépendance transitive dans un APK destiné à des appareils modestes.

**Conséquences.** Pas de Realtime, pas de gestion automatique des prekeys Storage : ces
besoins sont assumés hors périmètre v0.1. Un rafraîchissement de JWT sur `401` unique est
implémenté ; au-delà, l'erreur remonte.

### Session

`SessionManager` : signup anonyme si aucune session, rafraîchissement avant expiration,
`logout` propre. Le refresh token est chiffré via Android Keystore (`KeystoreBox`), jamais
stocké en clair. Aucune clé privée de projet n'est embarquée : seule la clé publique
`sb_publishable_…` est présente, ce qui est son rôle.

## 5. Couche proximité (`:net`)

| Composant | Rôle |
|---|---|
| `NearbyTransport` | Contrat commun : `start`, `send`, `incoming`, `stop` |
| `BleTransport` | GATT serveur + client, caractéristique d'écriture, fragmentation par frames |
| `WifiLocalTransport` | Découverte NSD + socket TCP, échange de frames |
| `EnvelopeCodec` | Frames `TOMATE1\|id\|i/n\|base64` (testable sur JVM) |
| `RelayEngine` | Politique : version, taille, TTL, hops, déduplication, quota, opt-out |
| `RelayStore` | Interface de stockage ; implémentation mémoire volontairement éphémère |

Le relais ne persiste **rien** sur disque : un appareil relais ne peut ni lire ni conserver
le contenu. C'est une décision de conception, pas une limite technique.

## 6. Sécurité

- Aucune cryptographie maison. `CryptoBox` s'appuie sur Android Keystore (AES-GCM).
- E2EE : interface `E2eeEngine`, implémentation unique `E2eeNotConfigured` qui **échoue
  explicitement** et alimente un libellé honnête dans l'UI.
- RLS côté serveur : `docs/05_SUPABASE_SETUP.md`, `supabase/migrations/0002_rls.sql`.
- Détail complet : `docs/04_SECURITE.md`.

## 7. Décisions techniques (journal)

| # | Décision | Justification |
|---|---|---|
| ADR-001 | AGP 9.5.1 + Gradle 9.9.0 + Kotlin intégré à AGP | `org.jetbrains.kotlin.android` incompatible avec AGP 9 (crash `AgpWithBuiltInKotlinAppliedCheck`), prouvé par build |
| ADR-002 | Client HTTP minimal plutôt que `supabase-kt` | auditabilité, testabilité, surface réduite |
| ADR-003 | `compileSdk 37`, `minSdk 26`, `targetSdk 36` | Compose 1.12.x exige `compileSdk 37` ; API 26 couvre 97 %+ des appareils |
| ADR-004 | Wi-Fi local via NSD + TCP, pas Wi-Fi Direct | fonctionne sans négociation de groupe, testable, permissions plus simples |
| ADR-005 | Relais en mémoire uniquement | honnêteté de la promesse « le relais ne garde rien » |
| ADR-006 | `security_invoker` sur la vue `feed` | sans cela, la vue contournerait les RLS |
| ADR-007 | Code de récupération haute entropie + Keystore, restauration non promise | pas de fonction serveur déployée : ne pas vendre une fonction absente |

## 8. Reproductibilité

```bash
source "$HOME/env.sh"            # JDK 21 + SDK 37 + Gradle 9.9
./gradlew :app:assembleDebug     # wrapper versionné
```

`tooling/bootstrap-android.sh` réinstalle la toolchain complète sur une machine vierge.
