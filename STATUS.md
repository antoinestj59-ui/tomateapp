# STATUS — TOMATE 0.1.1

Dernière vérification : **8 novembre 2026**, sur une VM Linux x86_64 (JDK Temurin 21.0.13,
Gradle 9.9.0, AGP 9.5.1, Android SDK 37).

Ce fichier décrit **uniquement ce qui a été réellement exécuté**. Tout ce qui n'a pas pu
l'être est listé comme non testé, jamais comme fonctionnel.

## 1. Résultats de build et de tests (exécutés)

| Commande | Résultat |
|---|---|
| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** |
| APK produit | `app/build/outputs/apk/debug/app-debug.apk` — **23 Mo** (`versionCode 2`, `0.1.1-debug`) |
| `:core:testDebugUnitTest :data:testDebugUnitTest :net:testDebugUnitTest` | **BUILD SUCCESSFUL**, exit code 0 |
| Tests unitaires exécutés | **net : 15** (11 relais + 4 codec), **data : 15** (dont 4 sur le compte hors ligne) |
| `:data:assembleDebugAndroidTest` | compilé, non exécuté (appareil requis) |
| CI GitHub Actions | workflow versionné, **pas encore exécuté sur GitHub** |

## 2. Création de compte hors ligne (nouveau)

| Aspect | Comportement |
|---|---|
| Sans réseau | l'onboarding crée une **identité locale** `local-<uuid>`, persistée dans DataStore ; l'app s'ouvre normalement |
| Deuxième lancement hors ligne | la même identité est restaurée (test unitaire) |
| Écritures hors ligne | publications, commentaires, réactions, messages, communautés : écrits dans Room, marqués « en attente », file d'attente outbox |
| Retour du réseau | `AccountLinker` crée le compte anonyme Supabase, réécrit l'id local vers l'id serveur (profil, publications, commentaires, messages, membres, propriétaire de communauté, payloads en attente), pousse le profil, puis vide la file |
| Pseudo déjà pris à la liaison | échec signalé par une bannière ; **aucune donnée locale supprimée**, l'utilisateur change de pseudo |
| Déconnexion | session, profil **et données locales** effacés (aucun contenu ne survit à un changement de compte) |

Preuves : `data/src/test/kotlin/tomate/data/OfflineIdentityTest.kt` (4 tests, Ktor MockEngine
hors ligne/en ligne), et compilation complète de l'app.

## 3. Correctifs de cette itération

| Problème | Correction |
|---|---|
| Erreur SQL `42601 syntax error at or near "supabase"` | causée par un **chemin de fichier collé** dans le SQL Editor ; `supabase/ALL_IN_ONE.sql` fourni + instructions explicites dans `docs/05` |
| Création de compte impossible sans réseau | identité locale + liaison automatique (ci-dessus) |
| `isHandleAvailable` hors ligne affichait « session expirée » | vérification différée, message explicite, création non bloquée |
| Vérification du pseudo à chaque frappe (rafale de requêtes) | debounce 450 ms |
| Profil non enregistré si l'envoi échouait | l'écriture locale réussit toujours, le drapeau `pendingSync` indique l'envoi restant |
| Avatars « spacer » de largeur nulle (padding sur `Spacer`) | `Modifier.width(...)` |
| Icônes `ArrowBack` / `Send` non miroir en RTL | `Icons.AutoMirrored.Filled.*` |
| Bannière globale absente (compte relié, pseudo pris) | bannière en haut de la navigation |

## 4. Fonctionnalités — état réel

### Terminées et compilées

Onboarding (y compris hors ligne), fil, composeur, réactions, commentaires, suppression,
communautés (création, adhésion, publications réservées), messagerie local-first avec
déduplication `client_id`, blocage et signalement, profil et réglages, session anonyme
Supabase + refresh chiffré Keystore, proximité BLE (GATT serveur + client), Wi-Fi local
(NSD + TCP), moteur de relais (TTL, hops, doublons, quota, opt-out).

### Partielles, signalées dans l'UI

| Fonctionnalité | Ce qui manque |
|---|---|
| Récupération de compte | code généré et stocké chiffré ; restauration impossible (pas de fonction serveur) |
| Avatars | bucket + politiques SQL fournis ; pas d'UI d'upload |
| Notifications | permission déclarée ; pas d'intégration FCM |

### Absentes, annoncées comme absentes

Chiffrement de bout en bout, suppression de compte côté serveur, appels audio/vidéo.

## 5. Ce qui n'a pas pu être testé ici

| Test | Raison | Statut |
|---|---|---|
| RLS réelle (7 assertions, 2 comptes) | migrations à appliquer par l'utilisateur | script prêt : `tooling/rls_smoke_test.sh` |
| Persistance Room sur appareil (5 tests) | aucun appareil, `/dev/kvm` inaccessible | compilé, non exécuté |
| BLE / Wi-Fi / relais multi-téléphones | aucune radio | protocole fourni (`docs/06` §3) |
| Liaison locale → Supabase sur un vrai projet | nécessite le réseau + migrations appliquées | logique couverte par tests unitaires, **pas de test d'intégration réel** |
| CI sur GitHub | exécution distante | workflow fourni |
| Captures d'écran | aucun émulateur possible | **non produites** |

## 6. Décisions techniques notables

| # | Décision | Raison |
|---|---|---|
| 1 | Kotlin intégré à AGP (pas de plugin `org.jetbrains.kotlin.android`) | incompatible avec AGP 9.5.1 — vérifié par échec de build |
| 2 | Client HTTP Supabase minimal (Ktor) | auditabilité, testabilité, ADR-002 |
| 3 | `compileSdk 37` | exigé par Compose 1.12.x |
| 4 | Identité locale `local-…` plutôt qu'un écran d'erreur | promesse produit : compte créable hors ligne |
| 5 | Réécriture de l'id local en une passe, puis envoi | reprise après interruption, aucune perte |
| 6 | Relais en mémoire uniquement | « un relais ne garde rien » reste vrai |
| 7 | `security_invoker` sur la vue `feed` | sinon contournement des RLS |

## 7. Prochaines étapes (par priorité)

| # | Étape | Effort |
|---|---|---|
| 1 | Appliquer `ALL_IN_ONE.sql` puis `./tooling/rls_smoke_test.sh` | 15 min (utilisateur) |
| 2 | Tester la liaison hors ligne → en ligne sur un appareil réel | 30 min |
| 3 | Test matériel BLE / Wi-Fi / relais (3 appareils) | 1 h |
| 4 | Suppression de compte serveur (Edge Function) | 1–2 j |
| 5 | Jalon E2EE complet (libsignal) | 2–3 semaines |
| 6 | Chiffrement de la base locale (SQLCipher) | 3–5 j |

## 8. Honnêteté de livraison

- Aucun bouton fictif : chaque action visible appelle un repository ou affiche une
  indisponibilité explicite.
- Aucune donnée simulée présentée comme réelle.
- Aucune clé secrète dans le dépôt, l'APK ou la conversation.
- Aucune publication Play Store effectuée.
