# 11 — DOSSIER DE REPRISE ET ÉTAT DES LIEUX (TOMATE 0.5.0)

Document complet de passation et de référence produit / technique pour TOMATE.

---

## 1. Ce qui est fait et prouvé

### 1.1 Socle offline-first Android
- **Room source de vérité locale** : outbox, backoff exponentiel avec jitter, rejeu idempotent. Prouvé par `SyncEngineTest` (11 tests) et rejeux réels.
- **Gestion des erreurs et file d'attente** : compteur distinct des opérations en attente et des refus définitifs avec actions « Réessayer » / « Abandonner ».
- **Conflits et autorisations** : 409 traité comme ressource déjà existante sans duplication ; 403 distinct de l'expiration de session (`AppError.Forbidden`).
- **Authentification & Session** : compte anonyme Supabase, session chiffrée par le Keystore Android, rafraîchissement automatique de JWT.
- **Compte hors ligne** : identifiant local `local-...` créé sans réseau puis rattaché automatiquement au retour de la connexion (`OfflineIdentityTest`).

### 1.2 Réseau social
- **Fil d'actualité** : publications texte, photo/vidéo (PWA testée, Android compilé), commentaires, réactions, suppression, édition, partage système, brouillons PWA, météo Open-Meteo avec cache daté, thèmes clair/sombre et `prefers-reduced-motion`.
- **Stories** : texte, photo, vidéo, vues nominatives, expiration 24 h (vérifiée par attente réelle), suppression par l'auteur, règles RLS vérifiées.
- **Communautés** : création publique/privée, adhésion, modération des demandes privées (`Pending` vs `Active`), liste des membres, publications réservées aux membres actifs, bannière et onglets.
- **Messagerie** : 1-1 et multi-destinataires, envoi local-first, déduplication `client_id`, citations, réactions 🍅❤️👍, renommage de conversation, suppression pour tout le monde avec cascade, flammes 🔥, filtres de conversation.

### 1.3 Notifications
- Table `0007` appliquée : déclencheurs serveur (messages, réactions, commentaires, adhésions), centre de notifications dans l'application, marquage lu / tout lu, badge non-lus, navigation directe. Chemin A→B vérifié en requêtes réelles. Code FCM conditionnel avec build valide sans `google-services.json`.

### 1.4 Appels WebRTC
- **PWA** : WebRTC complet (offre/réponse/candidats ICE), sonnerie, acceptation/refus, gestion micro/caméra, raccrochage. Signalisation vérifiée entre comptes réels.
- **Android** : moteur natif (`webrtc-sdk`), `CallEngine`, `CallController`, `CallOverlay`, configuration TURN.
- **Interopérabilité** : signalisation découpée par morceaux (3000 car.), formats Android et PWA interconnectés (`connectionState: connected`). Diagnostic TURN distinguant STUN seul et TURN opérationnel.

### 1.5 Sécurité et E2EE (0.5.0)
- **E2EE 1-1** : ECDH P-256 + HKDF-SHA256 + AES-256-GCM + clé éphémère par message + signature ECDSA + AAD.
- **Interopérabilité Android ↔ PWA** : validée par test JVM exécutant Node sur le module réel `web/js/e2ee.js`.
- **Appairage** : URI `tomate://pair`, QR ZXing validé en pixels, NDEF NFC validé en aller-retour, numéro de sécurité affiché, clé appairée épinglée prioritaire.
- **Réseau maillé** : routage avec TTL 7, déduplication LRU, split horizon, délai aléatoire, courrier borné avec tag rotatif quotidien, vérification de signature avant relais (13 tests JVM).
- **Preuve serveur** : message chiffré HTTP 201, relecture exacte par le destinataire, texte clair totalement absent de la base.

---

## 2. Ce qui est compilé mais jamais exécuté sur matériel réel

1. **L'application Android native complète** sur un vrai appareil ou émulateur KVM (l'environnement de build cloud ne dispose pas de `/dev/kvm`).
2. **Radio BLE** (serveur GATT + client) et **Wi-Fi local** (NSD + TCP).
3. **NFC physique** (écriture sur étiquette réelle).
4. **Scan caméra** (Android et PWA en environnement cloud sans périphérique vidéo).
5. **Média audio/vidéo des appels Android** sur un micro/haut-parleur physique.
6. **Réception push réelle** via les serveurs Google FCM.
7. **Chaîne de relais A → B → C sur 3 appareils physiques**.

---

## 3. Ce qui reste à compléter / chantiers futurs

1. **Suppression de compte côté serveur** (fonction Supabase + écran dédié, exigé pour le Play Store).
2. **Double Ratchet** (confidentialité persistante post-compromission).
3. **Chiffrement de groupe** (MLS ou protocole multi-destinataires).
4. **Courrier maillé persistant** sur stockage disque durable.
5. **File de modération avec interface d'administration**.
6. **Pièces jointes dans les messages Android** (alignement avec la PWA).
7. **Indicateur de saisie en temps réel**.

---

## 4. Guide technique pour les commandes

```bash
# Variables d'environnement
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

# Tests complets JVM
./gradlew :core:testDebugUnitTest :data:testDebugUnitTest :net:testDebugUnitTest :app:testDebugUnitTest --console=plain

# Compilation de l'APK Debug
./gradlew :app:assembleDebug --console=plain

# Vérification syntaxique JavaScript de la PWA
node --check web/js/app.js web/js/e2ee.js web/js/extras.js web/js/queue.js

# Scripts de validation Supabase réels
bash ./tooling/rls_smoke_test.sh
bash ./tooling/verify_040_reactions.sh
bash ./tooling/verify_050_e2ee.sh
```
