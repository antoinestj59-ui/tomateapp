# 🍅 TOMATE

Réseau social Android **natif**, **offline-first**, en français, avec communication locale
**Bluetooth Low Energy** et **Wi-Fi**, relais multi-appareils, et synchronisation Supabase.

> État : **version 0.1.0, APK debug fonctionnel**. Ce qui n'est pas testé est déclaré
> comme tel. Lire [`STATUS.md`](STATUS.md) avant toute démonstration.

## Sommaire

- [Fonctionnalités](#fonctionnalités)
- [Démarrage rapide](#démarrage-rapide)
- [Configuration Supabase](#configuration-supabase)
- [Architecture](#architecture)
- [Tests](#tests)
- [Documentation](#documentation)
- [Limites honnêtes](#limites-honnêtes)

## Fonctionnalités

| Domaine | État | Détail |
|---|---|---|
| Compte anonyme Supabase | ✅ | signup anonyme réel, JWT rafraîchi, session chiffrée via Keystore |
| Profil | ✅ | pseudo validé, nom, bio, visibilité, édition, déconnexion |
| Fil & publications | ✅ | composeur, fil local, suppression, réactions |
| Commentaires | ✅ | ajout, suppression, compteurs |
| Communautés | ✅ | création publique/privée, adhésion, publications réservées aux membres |
| Messagerie | ✅ | conversations, envoi local-first, déduplication `client_id` |
| Hors ligne | ✅ | Room source de vérité, outbox, backoff avec jitter |
| Modération | ✅ | signalement, blocage, suppression, rôles |
| Thème | ✅ | Material 3, clair/sombre/système, couleurs dynamiques |
| Proximité BLE | ⚠️ | implémentation réelle (GATT serveur + client), **non testée sur matériel** |
| Proximité Wi-Fi | ⚠️ | NSD + TCP réels, **non testés sur matériel** |
| Relais multi-appareils | ⚠️ | moteur de politique testé en unitaire, **pas de test 3 téléphones** |
| Chiffrement de bout en bout | ❌ | **non opérationnel et annoncé comme tel** |
| Notifications push | ❌ | hors périmètre v0.1 |
| Restauration de compte | ❌ | nécessite une fonction serveur non déployée |

## Démarrage rapide

### Prérequis

- Linux x86_64 (ou macOS/Windows avec JDK 21 et Android SDK)
- JDK 21, Android SDK `platforms;android-37`, `build-tools;36.1.0`
- Un appareil Android 8.0+ (API 26)

### Installation de la toolchain

```bash
./tooling/bootstrap-android.sh
source "$HOME/env.sh"
```

### Build

```bash
./gradlew :app:assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`applicationId` de la variante debug : `app.tomate.debug`.

### Tests

```bash
./gradlew :core:testDebugUnitTest :data:testDebugUnitTest :net:testDebugUnitTest
```

## Configuration Supabase

Le projet Supabase public est déjà renseigné : seules les **migrations** restent à
appliquer (option A, SQL Editor). Guide détaillé : [`docs/05_SUPABASE_SETUP.md`](docs/05_SUPABASE_SETUP.md).

```bash
# 1. Coller dans le SQL Editor, dans l'ordre :
#    supabase/migrations/0001_init.sql
#    supabase/migrations/0002_rls.sql
#    supabase/migrations/0003_storage.sql
# 2. Vérifier les RLS avec deux comptes réels :
./tooling/rls_smoke_test.sh
```

Aucune clé secrète n'est nécessaire ni présente dans le dépôt ou l'APK : seule la clé
publique (`sb_publishable_…`) est embarquée, ce qui est son usage prévu.

## Architecture

Quatre modules Gradle :

```
:app  → Compose Material 3, navigation, ViewModels
:data → Room, client Supabase (Ktor), repositories, SyncEngine, session
:net  → BLE, Wi-Fi local, codec d'enveloppes, moteur de relais
:core → modèles de domaine, Outcome/AppError, design system
```

Détails et décisions : [`docs/02_ARCHITECTURE.md`](docs/02_ARCHITECTURE.md).

## Tests

| Suite | Où | Exécutable sans appareil |
|---|---|---|
| Relais (11 tests) + codec (4 tests) | `net/src/test` | oui |
| Backoff, code de récupération, pseudos (11 tests) | `data/src/test` | oui |
| Persistance Room (5 tests) | `data/src/androidTest` | non (appareil requis) |
| RLS multi-comptes (7 assertions) | `tooling/rls_smoke_test.sh` | oui, après migrations |

Matrice complète et protocole multi-téléphones :
[`docs/06_TESTS_ET_LIVRAISON.md`](docs/06_TESTS_ET_LIVRAISON.md).

## Documentation

| Document | Contenu |
|---|---|
| [`docs/01_PRODUIT.md`](docs/01_PRODUIT.md) | vision, périmètre, écrans, non-fonctionnalités assumées |
| [`docs/02_ARCHITECTURE.md`](docs/02_ARCHITECTURE.md) | modules, flux, ADR, décisions techniques |
| [`docs/03_PROXIMITE.md`](docs/03_PROXIMITE.md) | protocole BLE/Wi-Fi, enveloppes, relais |
| [`docs/04_SECURITE.md`](docs/04_SECURITE.md) | modèle de menace, RLS, E2EE, secrets |
| [`docs/05_SUPABASE_SETUP.md`](docs/05_SUPABASE_SETUP.md) | migrations, SQL Editor, dépannage |
| [`docs/06_TESTS_ET_LIVRAISON.md`](docs/06_TESTS_ET_LIVRAISON.md) | matrice de tests, multi-appareils, Play Store |
| [`STATUS.md`](STATUS.md) | état réel, vérifié, des fonctionnalités et des tests |

## Limites honnêtes

1. **E2EE absent.** Les messages sont protégés en transit (TLS) mais pas de bout en bout.
   L'interface l'affiche clairement, partout où des messages apparaissent.
2. **BLE/Wi-Fi non testés sur matériel.** Le code est réel, la radio est absente de
   l'environnement de développement.
3. **Relais non validé à trois appareils.** Le moteur de politique est couvert par des
   tests unitaires (TTL, hops, doublons, quota, opt-out).
4. **Restauration de compte indisponible.** Elle exigerait une fonction serveur.
5. **Suppression de compte serveur à implémenter** avant toute publication Play Store.
6. **Aucune publication n'a été effectuée** sur le Play Store, et aucune ne le sera sans
   autorisation explicite.

## Licence

Projet privé — tous droits réservés à l'auteur du dépôt.
