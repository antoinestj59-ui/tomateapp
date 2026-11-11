# 01 — TOMATE : produit, périmètre et écrans

## 1. Vision

TOMATE est un réseau social Android **natif**, **offline-first**, en **français**, dont la
promesse tient en trois points :

1. **Ça marche sans réseau.** Publier, commenter, réagir, discuter : tout est écrit
   localement d'abord, puis synchronisé quand la connexion revient.
2. **Ça marche sans serveur à proximité.** Deux téléphones peuvent échanger directement en
   Bluetooth Low Energy ou en Wi-Fi local, et un troisième peut relayer sans lire.
3. **Ça ne ment pas.** Aucun compteur inventé, aucun bouton décoratif, chiffrement de
   bout en bout 1-1 réel avec clés appairées, et statuts honnêtes sur l'état du réseau.

Public visé : usage communautaire de proximité (quartier, événement, établissement), là où
la connexion est mauvaise, intermittente ou inexistante.

## 2. Principes de conception

| Principe | Traduction concrète |
|---|---|
| Offline-first | Room est la source de vérité locale ; le réseau est un synchroniseur, pas un prérequis |
| Identité légère | Compte anonyme Supabase, pseudo choisi localement, aucun e-mail obligatoire |
| Chiffrement réel | E2EE 1-1 basé sur ECDH P-256 + HKDF + AES-GCM + ECDSA, clés stockées chiffrées |
| Honnêteté de l'état | Chaque écran a ses états : chargement, vide, erreur, hors ligne, état E2EE réel |
| Sobriété | Pas de publicité, pas de traqueur, pas de permission inutile |
| Contenu opaque en relais | Un relais transporte une enveloppe chiffrée ou opaque, jamais du contenu lisible |
| Modération réelle | Signalement, blocage, suppression, rôles de communauté réels |

## 3. Écrans livrés

| Écran | Contenu réel | Dépendances |
|---|---|---|
| **Onboarding** | Pseudo validé (3–20 caractères), visibilité du profil, création avec ou sans connexion | `ProfileRepository`, Supabase |
| **Accueil / Fil** | Stories (24 h), composeur, fil local, réactions, commentaires, média photo/vidéo, météo | Room, `FeedRepository`, `StoryRepository`, `SyncEngine` |
| **Stories** | Texte, fond coloré, photo/vidéo, visionneuse, marquage lu nominatif, suppression auteur, expiration 24 h | Room, `StoryRepository`, bucket `stories` |
| **Détail publication** | Publication, commentaires avec suppression, réactions, signalement, blocage de l'auteur | Room, modération |
| **Communautés** | Liste, création publique/privée, adhésion, modération des demandes, fil réservé aux membres | Room, `CommunityRepository` |
| **Messages** | Liste des conversations avec filtres (Toutes, Privées, Groupes, Non lues), flammes 🔥, statut E2EE | Room, `MessagingRepository` |
| **Conversation** | Envoi local-first, déduplication `client_id`, réponses citées, réactions, chiffrement 1-1 réel | Room, `E2eeService`, crypto |
| **Appairage** | Lien `tomate://pair`, QR ZXing, étiquette NFC NDEF, numéro de sécurité partagé | `PairingQr`, `PairingNfc`, `E2eeStore` |
| **Notifications** | Liste des notifications (messages, réactions, adhésions), marquage lu/tout lu, navigation directe | `NotificationRepository` |
| **Appels** | WebRTC 1-1 audio/vidéo, signalisation découpée, diagnostic STUN/TURN, interface d'appel | `CallEngine`, `CallController` |
| **Proximité / Maillage** | Découverte BLE et Wi-Fi local, routage maillé TTL 7, déduplication, boîte de courrier bornée | `:net` |
| **Profil** | Édition du profil, identifiant de compte, dialogue E2EE dédié, code de récupération, déconnexion | Room, Keystore, modération |

## 4. Ce qui est différé / hors périmètre 0.5.0

| Fonction | Raison / Statut |
|---|---|
| Chiffrement des conversations de groupe | Requiert un protocole de groupe (MLS) complexe, différé après la v0.5 |
| Suppression de compte côté serveur | Exigée pour le Play Store, à intégrer avant mise en production publique |
| Test sur parc physique de téléphones | L'environnement cloud n'embarque pas d'appareils physiques pour valider la radio |
| Double Ratchet | Clés révoquées post-compromission non prises en charge dans la v0.5 |
