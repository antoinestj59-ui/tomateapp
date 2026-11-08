# 06 — Tests, protocole multi-appareils et livraison

Ce document décrit **ce qui est testé, comment, et ce qui ne peut pas l'être** dans
l'environnement de développement utilisé (VM Linux sans radio ni émulateur KVM). Aucune
ligne ci-dessous ne doit être lue comme « testé » sans la colonne *Statut* correspondante.

## 1. Matrice de tests

| # | Domaine | Test | Type | Statut |
|---|---|---|---|---|
| 1 | Build | `./gradlew :app:assembleDebug` (compileSdk 37, AGP 9.5.1, Kotlin intégré) | réel | voir `STATUS.md` |
| 2 | Build | génération de l'APK debug installable | réel | voir `STATUS.md` |
| 3 | Core | règles de profil, erreurs utilisateur | unitaire JVM | automatisé |
| 4 | Data | validation des pseudos (format + réservés + normalisation) | unitaire JVM | automatisé |
| 5 | Data | backoff : bornes, plafond, arrêt après N tentatives, jitter | unitaire JVM | automatisé |
| 6 | Data | code de récupération : format, entropie (50 codes distincts), normalisation | unitaire JVM | automatisé |
| 7 | Net | relais : acceptation, livraison locale, TTL, hops, quota, opt-out, taille, version | unitaire JVM (15 tests) | automatisé |
| 8 | Net | codec d'enveloppes : aller-retour, frames tronquées, base64 invalide | unitaire JVM (4 tests) | automatisé |
| 9 | Supabase | health, settings, signup anonyme réel | réel (réseau) | **exécuté** |
| 10 | Supabase | RLS multi-comptes (7 assertions) | réel (réseau) | `tooling/rls_smoke_test.sh` — bloqué tant que les migrations ne sont pas appliquées |
| 11 | Room | persistance, migrations de schéma | instrumenté | **non exécuté** (pas d'émulateur) |
| 12 | UI | navigation, états, accessibilité | instrumenté Compose | **non exécuté** |
| 13 | BLE | découverte + GATT serveur/client réels | matériel | **non exécuté** (pas de radio) |
| 14 | Wi-Fi | NSD + TCP entre deux appareils | matériel | **non exécuté** |
| 15 | Relais | 3 appareils, TTL, déduplication réelle | matériel | **non exécuté** |
| 16 | Batterie | consommation en relais actif | matériel | **non exécuté** |

## 2. Commandes de test

```bash
# Tests unitaires JVM (core, data, net) — exécutables partout, sans appareil
./gradlew :core:testDebugUnitTest :data:testDebugUnitTest :net:testDebugUnitTest

# APK debug
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Vérification RLS réelle (après migrations Supabase)
./tooling/rls_smoke_test.sh

# Tests instrumentés (nécessite un appareil ou un émulateur avec KVM)
./gradlew :app:connectedDebugAndroidTest
```

## 3. Protocole de test multi-appareils (BLE + Wi-Fi + relais)

Ce protocole exige **au moins trois téléphones Android 8.0+ (API 26+)** avec Bluetooth
Low Energy et Wi-Fi. Il n'a pas été exécuté dans cet environnement.

### Préparation

1. Installer le même APK debug sur les trois appareils.
   `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`
2. Sur chaque appareil : créer un compte anonyme, choisir un pseudo distinct
   (`bleu`, `vert`, `rouge` par exemple).
3. Accorder les permissions : Bluetooth (API 31+ : `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`,
   `BLUETOOTH_CONNECT`) et, pour le Wi-Fi, `NEARBY_WIFI_DEVICES` (API 33+) ou la
   localisation (API ≤ 32). Refuser une permission doit afficher un état explicite, pas un
   écran vide.
4. Laisser le Wi-Fi actif et les trois appareils sur le même réseau local.

### Scénario A — Découverte

| Étape | Attendu |
|---|---|
| A1 | Chaque appareil voit les deux autres dans « Proximité » en moins de 30 s |
| A2 | Couper le Bluetooth d'un appareil : il disparaît en moins de 60 s |
| A3 | Refuser la permission Bluetooth : message d'erreur explicite, bouton de réessai |

### Scénario B — Message direct

| Étape | Attendu |
|---|---|
| B1 | `bleu` envoie un message à `vert` en BLE : il arrive une seule fois |
| B2 | Couper le Wi-Fi de `vert` : le message arrive quand même (BLE) |
| B3 | Envoyer 20 messages : aucun doublon, aucun message perdu |
| B4 | Envoyer un message de 60 Ko : refus explicite (limite d'enveloppe) |

### Scénario C — Relais à trois

| Étape | Attendu |
|---|---|
| C1 | `bleu` et `rouge` hors de portée ; `vert` au milieu, relais activé |
| C2 | `bleu` envoie à `rouge` : `vert` transmet sans afficher le contenu |
| C3 | `vert` désactive le relais dans Réglages : la livraison échoue proprement |
| C4 | Le même message transmis deux fois par deux chemins : un seul exemplaire livré |
| C5 | Laisser 24 h : aucune enveloppe expirée ne reste en file |

### Scénario D — Robustesse

| Étape | Attendu |
|---|---|
| D1 | Tuer l'app de `vert` pendant un transfert : aucune donnée corrompue au redémarrage |
| D2 | Activer le mode avion : les messages partent en file d'attente, état « en attente » |
| D3 | Désactiver le mode avion : la file se vide, sans doublon |
| D4 | Écran verrouillé 30 min, relais actif : consommation mesurée avec Battery Historian |

### Journal de résultats

À remplir après exécution (aucun résultat ne doit être inventé) :

```
Appareils : ______ (modèle, API) / ______ / ______
Date : ______
A1: PASS/FAIL   A2: ___   A3: ___
B1: ___         B2: ___   B3: ___   B4: ___
C1: ___         C2: ___   C3: ___   C4: ___   C5: ___
D1: ___         D2: ___   D3: ___   D4: ___
```

## 4. Tests instrumentés fournis

`data/src/androidTest/kotlin/tomate/data/RoomPersistenceTest.kt` vérifie la persistance
locale, indépendamment de toute radio :

| Test | Vérifie |
|---|---|
| `profile_isPersistedAndObserved` | un profil écrit est relu et observé via un `Flow` |
| `onlyOneRowIsMe` | une seule ligne `isMe = 1` est exposée comme identité locale |
| `deletedPostsAreHiddenFromTheFeed` | une publication marquée supprimée disparaît du fil |
| `signOutRemovesOnlyMyProfile` | la déconnexion ne supprime que l'identité locale, pas les profils en cache |
| `hiddenPostIsNotDeletedButFlagged` | la suppression locale est un drapeau jusqu'à la synchronisation |

Compilation vérifiée (`:data:assembleDebugAndroidTest`). Exécution :
`./gradlew :data:connectedDebugAndroidTest`. **Sans appareil ou émulateur KVM, ces tests
ne peuvent pas être lancés ici** : ils sont livrés prêts à l'emploi, non exécutés.

## 5. Livraison

### APK debug

- Chemin : `app/build/outputs/apk/debug/app-debug.apk`
- Signature : clé debug locale (non publiable en production)
- `applicationId` debug : `app.tomate.debug` (suffixe pour cohabiter avec une version release)

### CI GitHub Actions

`.github/workflows/android.yml` : JDK 21, SDK Android 37, tests unitaires, lint
(non bloquant), `assembleDebug`, publication de l'APK et des rapports en artefacts.

### Préparation Play Store (non publié)

| Élément | État |
|---|---|
| `applicationId` définitif `app.tomate` | prêt |
| `versionCode` / `versionName` | `1` / `0.1.0` |
| Signature release | **à créer** (keystore utilisateur, hors dépôt) |
| `isMinifyEnabled` + R8 | désactivé ; à activer après tests de release |
| Politique de confidentialité | à rédiger et héberger (obligatoire) |
| Formulaire Data Safety | à remplir : contenus publiés, messages, identifiants |
| Suppression de compte | **obligatoire** : l'app permet la déconnexion et la suppression locale ; la suppression côté serveur doit être ajoutée (Edge Function) |
| Classification par âge | à déclarer (contenu généré par les utilisateurs) |
| Tests pré-lancement | à effectuer sur Play Console |

**Aucune publication n'a été effectuée et aucune ne sera effectuée sans autorisation
explicite.**

## 6. Limites connues de l'environnement de développement

| Limite | Conséquence |
|---|---|
| Pas de `/dev/kvm` accessible | aucun émulateur → pas de test UI automatique |
| Pas de radio Bluetooth/Wi-Fi | pas de test BLE/NSD réel |
| Pas de second appareil | relais multi-appareils non exécuté |
| Pas de clé `service_role` | migrations appliquées par l'utilisateur (option A) |
| Réseau sortant limité aux API publiques | pas de test de charge serveur |

Ces limites sont reportées telles quelles dans `STATUS.md` et dans le rapport final.
