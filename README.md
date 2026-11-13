# 🍅 TOMATE

Réseau social **Android natif** et **PWA installable**, **offline-first**, en français, avec
communication locale **Bluetooth Low Energy** et **Wi-Fi**, relais multi-appareils, et
synchronisation Supabase.

> État : **version 0.6.0**, APK debug et PWA publiés en aperçu. Le compte se crée **avec ou
> sans connexion**. Ce qui n'est pas testé est déclaré comme tel : lire [`STATUS.md`](STATUS.md)
> avant toute démonstration.
>
> PWA : https://site-1b6f0f4eb331483ca0b74abd4600dd43.freebuff.page

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
| **Création de compte hors ligne** | ✅ | identité locale `local-…`, tout reste utilisable, liaison Supabase automatique au retour du réseau |
| **PWA installable** | ✅ | `web/`, même backend, hors ligne (IndexedDB + service worker), permissions expliquées |
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
| Chiffrement de bout en bout | ⚠️ | 1-1 réel (P-256 + AES-GCM), groupes non chiffrés, pas de Double Ratchet |
| Notifications push | ⚠️ | centre de notifications réel ; envoi FCM codé, jamais déclenché |
| Restauration de compte | ❌ | nécessite une fonction serveur non déployée |
| Demandes d'amis | ⚠️ | code livré, migration `0012` à appliquer |

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

## Version web (PWA)

```bash
cd web && python3 -m http.server 3000 --bind 0.0.0.0
```

Même compte et mêmes données que l'Android : auth anonyme, fil, publications, réactions,
commentaires, messagerie texte, communautés, météo Open-Meteo, file d'attente hors ligne,
page de permissions réellement détectées. Le mesh BLE/Wi-Fi et le relais restent
**Android uniquement** : aucun navigateur ne les permet, et la PWA l'explique.

Détail, limites et preuves de test : [`docs/07_PWA.md`](docs/07_PWA.md).

## Configuration Supabase

Le projet Supabase public est déjà renseigné : seules les **migrations** restent à
appliquer (option A, SQL Editor). Guide détaillé : [`docs/05_SUPABASE_SETUP.md`](docs/05_SUPABASE_SETUP.md).

Dans **SQL Editor → New query**, coller le **contenu** de
[`supabase/ALL_IN_ONE.sql`](supabase/ALL_IN_ONE.sql) (bouton *Raw* / *Copier le fichier*),
puis **Run** une seule fois. Ce fichier contient les trois migrations dans le bon ordre.

> Ne collez jamais un **chemin** de fichier (`supabase/migrations/0003_storage.sql`) : le SQL
> Editor n'exécute que du SQL et répondrait `syntax error at or near "supabase"`.

Ensuite, vérifier les RLS avec deux comptes réels :

```bash
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
| Backoff, récupération, pseudos, compte hors ligne (15 tests) | `data/src/test` | oui |
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
| [`docs/07_PWA.md`](docs/07_PWA.md) | PWA : fonctions, limites navigateur, tests effectués |
| [`STATUS.md`](STATUS.md) | état réel, vérifié, des fonctionnalités et des tests |

## Limites honnêtes

1. **E2EE absent.** Les messages sont protégés en transit (TLS) mais pas de bout en bout.
   L'interface l'affiche clairement, partout où des messages apparaissent.
2. **BLE/Wi-Fi non testés sur matériel.** Le code est réel, la radio est absente de
   l'environnement de développement.
3. **Relais non validé à trois appareils.** Le moteur de politique est couvert par des
   tests unitaires (TTL, hops, doublons, quota, opt-out).
4. **Restauration de compte indisponible.** Elle exigerait une fonction serveur.
5. **Pseudo choisi hors ligne :** il est vérifié à la liaison au réseau. S'il est déjà pris,
   l'app le signale et vos données locales restent intactes ; il faut alors changer de pseudo.
6. **La déconnexion efface les données locales** de l'appareil (comportement confirmé dans
   l'interface) : c'est voulu pour qu'aucun contenu ne reste visible après un changement de compte.
7. **PWA : capabilities limitées par le navigateur.** Pas de BLE en arrière-plan, pas de
   Wi-Fi Direct, pas de relais : ces fonctions restent Android. L'écran Réseau les liste.
8. **Suppression de compte serveur à implémenter** avant toute publication Play Store.
9. **Aucune publication Play Store n'a été effectuée**, et aucune ne le sera sans
   autorisation explicite. La PWA n'est déployée qu'en **aperçu**, pas en production.

## Licence

Projet privé — tous droits réservés à l'auteur du dépôt.
