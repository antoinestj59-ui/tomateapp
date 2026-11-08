# STATUS — TOMATE 0.1.0

Dernière vérification : **8 novembre 2026**, sur une VM Linux x86_64 (JDK Temurin 21.0.13,
Gradle 9.9.0, AGP 9.5.1, Android SDK 37).

Ce fichier décrit **uniquement ce qui a été réellement exécuté**. Tout ce qui n'a pas pu
l'être est listé comme non testé, jamais comme fonctionnel.

## 1. Résultats de build et de tests (exécutés)

| Commande | Résultat |
|---|---|
| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** |
| APK produit | `app/build/outputs/apk/debug/app-debug.apk` — **23 Mo**, installable |
| `./gradlew :core:testDebugUnitTest :data:testDebugUnitTest :net:testDebugUnitTest` | **BUILD SUCCESSFUL**, aucune erreur |
| Tests unitaires exécutés | **net : 15** (11 relais + 4 codec), **data : 11** (backoff 4, récupération 4, pseudos 3) |
| `./gradlew :data:assembleDebugAndroidTest` | **BUILD SUCCESSFUL** (tests instrumentés compilés, non exécutés) |
| `./gradlew wrapper --gradle-version 9.9.0` | wrapper versionné généré |
| CI GitHub Actions | workflow écrit et versionné, **pas encore exécuté sur GitHub** |

Chiffres vérifiables :

```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
grep -h -o 'tests="[0-9]*"' net/build/test-results/testDebugUnitTest/*.xml
```

## 2. Fonctionnalités — état réel

### Terminées et compilées

| Fonctionnalité | Preuve |
|---|---|
| Onboarding (pseudo validé, visibilité, avertissement compte anonyme) | code compilé, validation testée en unitaire |
| Fil, composeur, réactions, suppression | Room + repository + UI compilés |
| Commentaires (ajout, suppression, compteur) | idem |
| Communautés (création, adhésion, publications réservées aux membres) | idem |
| Messagerie local-first avec déduplication `client_id` | idem + contrainte SQL |
| Outbox + backoff avec jitter | tests unitaires (4) |
| Blocage et signalement | Room + RLS |
| Réglages (thème, couleurs dynamiques, relais, batterie) | `SettingsStore` + UI |
| Session Supabase anonyme + refresh chiffré Keystore | endpoints réels testés pendant l'audit |
| Proximité BLE (GATT serveur + client) | code compilé, **pas de test matériel** |
| Wi-Fi local (NSD + TCP) | code compilé, **pas de test matériel** |
| Moteur de relais (TTL, hops, doublons, quota, opt-out) | 11 tests unitaires |

### Partielles, explicitement signalées dans l'UI

| Fonctionnalité | Ce qui manque |
|---|---|
| Code de récupération de compte | généré et stocké chiffré ; **restauration impossible** (pas de fonction serveur) |
| Avatars | bucket + politiques SQL fournis ; **pas d'UI d'upload** |
| Notifications | permission déclarée ; **pas d'intégration FCM** |

### Absentes, annoncées comme absentes

| Fonctionnalité | Où l'app le dit |
|---|---|
| Chiffrement de bout en bout | Profil, Conversation, Réglages, `docs/04_SECURITE.md` |
| Suppression de compte côté serveur | `docs/06_TESTS_ET_LIVRAISON.md` §5 (obligatoire avant Play Store) |
| Appels audio/vidéo | hors périmètre |

## 3. Ce qui n'a pas pu être testé ici

| Test | Raison | Statut |
|---|---|---|
| RLS réelle (7 assertions, 2 comptes) | migrations à appliquer par l'utilisateur (option A) | script prêt : `tooling/rls_smoke_test.sh` |
| Persistance Room sur appareil (5 tests) | aucun appareil, `/dev/kvm` inaccessible | compilé, non exécuté |
| Navigation / UI instrumentée | aucun émulateur possible | non fourni comme validé |
| BLE entre 2 téléphones | aucune radio | protocole fourni (`docs/06` §3) |
| Wi-Fi local entre 2 téléphones | aucune radio | protocole fourni |
| Relais 3 téléphones | aucun second appareil | protocole fourni |
| Consommation batterie | matériel requis | non mesuré |
| CI sur GitHub | pas encore poussé au moment de la rédaction | workflow fourni |

## 4. Décisions techniques notables

| # | Décision | Raison |
|---|---|---|
| 1 | Kotlin intégré à AGP (pas de plugin `org.jetbrains.kotlin.android`) | incompatible avec AGP 9.5.1 — vérifié par échec de build |
| 2 | Client HTTP Supabase minimal (Ktor) au lieu de `supabase-kt` | auditabilité, testabilité, ADR-002 |
| 3 | `compileSdk 37` | exigé par Compose 1.12.x |
| 4 | Relais en mémoire uniquement | « un relais ne garde rien » reste vrai |
| 5 | Vrai client HTTP + vraies RLS, aucune donnée simulée | exigence du cahier des charges |
| 6 | `security_invoker` sur la vue `feed` | sinon contournement des RLS |

## 5. Ce que l'utilisateur doit faire (interventions externes uniquement)

1. **Appliquer les migrations** dans le SQL Editor Supabase : `0001`, `0002`, `0003`
   (voir `docs/05_SUPABASE_SETUP.md`).
2. Lancer `./tooling/rls_smoke_test.sh` pour valider la RLS avec deux comptes réels.
3. Tester l'APK sur un appareil physique (BLE/Wi-Fi/relais) avec le protocole de
   `docs/06_TESTS_ET_LIVRAISON.md` §3.
4. Pour une release : créer un keystore, activer R8, rédiger la politique de
   confidentialité, implémenter la suppression de compte serveur.
5. **Ne rien publier sur le Play Store** sans décision explicite de l'utilisateur.

## 6. Prochaines étapes techniques (par priorité)

| # | Étape | Effort |
|---|---|---|
| 1 | Appliquer les migrations + exécuter le test RLS | 15 min (utilisateur) |
| 2 | Test matériel BLE/Wi-Fi/relais (protocole fourni) | 1 h avec 3 appareils |
| 3 | Écrire les tests instrumentés de navigation | 1 j |
| 4 | Suppression de compte serveur (Edge Function) | 1–2 j |
| 5 | Jalon E2EE complet (libsignal) | 2–3 semaines |
| 6 | Chiffrement de la base locale (SQLCipher) | 3–5 j |
| 7 | Notifications push (FCM + fonction serveur) | 1 sem. |
| 8 | Upload d'avatars dans l'app | 2 j |

## 7. Honnêteté de livraison

- Aucun bouton fictif : chaque action visible appelle un repository ou affiche un état
  d'indisponibilité explicite.
- Aucune donnée simulée présentée comme réelle : avant migrations, l'app affiche une
  erreur `SchemaMissing` avec la marche à suivre.
- Aucune clé secrète dans le dépôt, l'APK ou la conversation.
- Aucune publication Play Store effectuée.
