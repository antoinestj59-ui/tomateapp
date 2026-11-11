# 🍅 TOMATE

Réseau social **Android natif** et **PWA installable**, **offline-first**, en français, avec
chiffrement de bout en bout 1-1, communication locale **Bluetooth Low Energy** et **Wi-Fi**,
relais maillé multi-appareils, et synchronisation Supabase.

> État : **version 0.5.0**, APK debug et PWA compilés et vérifiés. Le compte se crée **avec ou
> sans connexion**. Ce qui n'est pas testé sur matériel est déclaré honnêtement comme tel :
> lire [`STATUS.md`](STATUS.md) et [`docs/11_HANDOFF.md`](docs/11_HANDOFF.md).
>
> Aperçu PWA : https://site-1b6f0f4eb331483ca0b74abd4600dd43.freebuff.page

## Sommaire

- [Fonctionnalités](#fonctionnalités)
- [Démarrage rapide](#démarrage-rapide)
- [Configuration Supabase](#configuration-supabase)
- [Architecture](#architecture)
- [Tests et validation](#tests-et-validation)
- [Documentation](#documentation)
- [Limites honnêtes](#limites-honnêtes)

## Fonctionnalités

| Domaine | État | Détail |
|---|---|---|
| Compte anonyme Supabase | ✅ | signup anonyme réel, JWT rafraîchi, session chiffrée via Keystore |
| **Création de compte hors ligne** | ✅ | identité locale `local-…`, tout reste utilisable, liaison Supabase automatique au retour du réseau |
| **PWA installable** | ✅ | `web/`, même backend, hors ligne (IndexedDB + service worker), permissions expliquées |
| Profil | ✅ | pseudo validé, nom, bio, visibilité, édition, déconnexion |
| Fil & publications | ✅ | composeur, fil local, suppression, réactions, édition, partage, photo/vidéo |
| Stories | ✅ | texte, photo/vidéo, expiration 24 h, vues nominatives, suppression auteur |
| Commentaires | ✅ | ajout, suppression, compteurs réels |
| Communautés | ✅ | création publique/privée, adhésion, liste des membres, fil réservé aux membres |
| Messagerie | ✅ | 1-1 et groupes, envoi local-first, déduplication `client_id`, citations, réactions, flammes 🔥 |
| **Chiffrement de bout en bout (E2EE)** | ✅ | 1-1 opérationnel : ECIES P-256 + AES-GCM + signatures ECDSA, interopérabilité Android↔PWA vérifiée, appairage QR/NFC/lien |
| Notifications | ✅ | centre de notifications dans l'application, déclencheurs serveur réels, code FCM conditionnel |
| Appels WebRTC | ✅ | PWA opérationnelle, Android natif compilé, signalisation découpée et diagnostic TURN |
| Hors ligne | ✅ | Room source de vérité, outbox, backoff exponentiel avec jitter |
| Modération | ✅ | signalement, blocage, suppression, rôles Owner / Moderator |
| Thème | ✅ | Material 3, clair/sombre/système, animations adaptées |
| Proximité BLE / Wi-Fi | ⚠️ | implémentation réelle (GATT serveur + client, NSD + TCP), **non testée sur matériel physique** |
| Relais maillé | ⚠️ | politique testée (TTL, dédup LRU, split horizon, courrier borné), **non testée sur 3 téléphones physiques** |

## Démarrage rapide

### Prérequis

- Linux x86_64 (ou macOS/Windows avec JDK 21 et Android SDK)
- JDK 21, Android SDK `platforms;android-37`, `build-tools;36.1.0`
- Un appareil Android 8.0+ (API 26)

### Commandes usuelles

```bash
# Tests unitaires JVM (108 tests)
./gradlew :core:testDebugUnitTest :data:testDebugUnitTest :net:testDebugUnitTest :app:testDebugUnitTest --console=plain

# Assemblage de l'APK
./gradlew :app:assembleDebug --console=plain

# Validation syntaxique Web
node --check web/js/app.js web/js/e2ee.js web/js/extras.js web/js/queue.js

# Scripts de validation Supabase
bash ./tooling/rls_smoke_test.sh
bash ./tooling/verify_040_reactions.sh
bash ./tooling/verify_050_e2ee.sh
```

## Documentation

- [`docs/01_PRODUIT.md`](docs/01_PRODUIT.md) — Spécification produit et parcours
- [`docs/02_ARCHITECTURE.md`](docs/02_ARCHITECTURE.md) — Architecture technique et modules
- [`docs/04_SECURITE.md`](docs/04_SECURITE.md) — Modèle de sécurité et cryptographie
- [`docs/07_PWA.md`](docs/07_PWA.md) — Client web PWA
- [`docs/08_RESEAU_ET_CHIFFREMENT.md`](docs/08_RESEAU_ET_CHIFFREMENT.md) — Protocole E2EE et maillage
- [`docs/09_MATRICE_FONCTIONNALITES.md`](docs/09_MATRICE_FONCTIONNALITES.md) — Matrice d'audit
- [`docs/11_HANDOFF.md`](docs/11_HANDOFF.md) — Dossier de reprise complet
- [`STATUS.md`](STATUS.md) — Journal d'avancement et preuves d'exécution
