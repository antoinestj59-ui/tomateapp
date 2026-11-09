# 08 — Réseau maillé, chaîne de transmission et chiffrement

Ce document est une **étude de conception**. Il sépare strictement :

1. ce que TOMATE fait aujourd'hui, vérifié ;
2. ce que font les projets comparables, avec leurs sources ;
3. ce qui reste à construire, avec des critères d'acceptation mesurables.

Rien de ce qui suit la section 5 n'est implémenté.

## 1. Ce qui existe déjà dans TOMATE

| Brique | État | Vérification |
|---|---|---|
| Enveloppe opaque `MessageEnvelope` (id, conversation, émetteur, TTL, `hopCount`, version, payload ≤ 64 Kio) | livrée | tests JVM `EnvelopeCodecTest`, `RelayEngineTest` |
| Frames `TOMATE1\|id\|i/n\|base64`, réassemblage dans le désordre, doublons ignorés | livrée | 4 tests JVM |
| `RelayEngine` : version inconnue, enveloppe malformée, trop grosse, expirée, `hopCount` dépassé, doublon, quota, opt-out | livrée | 11 tests JVM |
| Transports BLE (GATT serveur + client) et Wi-Fi local (NSD + TCP) | code livré | **jamais exécuté sur matériel** (aucune radio ici) |
| Relais activé par défaut | oui (`SettingsStore.relayEnabled = true`) | lecture du code |
| File de relais persistante | **non** : mémoire, 200 entrées, rien sur disque | décision ADR-005 |
| Chiffrement de bout en bout | **non** : `E2eeNotConfigured`, l'UI l'annonce | `docs/04_SECURITE.md` |
| Chaîne de messages entre deux comptes, en ligne | **oui, vérifiée** : conversation créée par un compte, membre ajouté, messages des deux côtés lus par l'autre (4 × HTTP 201) | tests réseau du 9 novembre 2026 |
| Ajout d'ami par QR (PWA) | **oui** : QR généré (payload `tomato://add?u=…`), collage du lien, création de conversation même hors ligne, scan caméra si `BarcodeDetector` est disponible | test navigateur |
| Flammes 🔥 | **oui** : jours consécutifs où **les deux** ont écrit, calculés à partir des messages réels, affichés hors ligne | test navigateur (🔥 2 sur deux jours réels) |

## 2. Ce que font les projets comparables

### bitchat (BLE, sans Internet)

- Maille Bluetooth : chaque appareil relaie, découverte et routage multi-sauts.
- Chiffrement de lien avec **Noise** ; les messages privés passent par un « courier » :
  dépôt, transport borné, remise différée quand le destinataire redevient joignable.
- Files, fragments et état par pair **bornés** ; déduplication ; les messages publics
  récents sont archivés ~6 h pour survivre à un redémarrage et traverser une partition.
- Repli Internet via Nostr, et « ponts » entre mailles.
- Sources : [privacy-assessment.md](https://raw.githubusercontent.com/permissionlesstech/bitchat/main/docs/privacy-assessment.md),
  [bitchat.free](https://bitchat.free/).

**À retenir** : sans rendez-vous central, il faut un **courier** (store-and-forward borné)
et une **déduplication** solide. C'est exactement le chaînon manquant de TOMATE.

### Briar (Bramble)

- BTP : XSalsa20/Poly1305, secret partagé, **forward secrecy** annoncée.
- BQP : le QR code échange la clé publique X25519 **et** les adresses de transport.
- BSP : synchronisation de graphes de messages pour réseaux tolérants aux délais
  (les messages peuvent être retardés, perdus, réordonnés, dupliqués).
- Transports : Bluetooth, Wi-Fi local, Tor, cartes mémoire.
- Sources : [how it works](https://briarproject.org/how-it-works),
  [BSP](https://code.briarproject.org/akwizgran/briar-spec/blob/master/protocols/BSP.md),
  [BTP/BQP](https://www.net.in.tum.de/fileadmin/TUM/NET/NET-2021-05-1/NET-2021-05-1_01.pdf).

**À retenir** : le QR n'est pas qu'un ajout d'ami, c'est **l'échange de clés** et des
adresses. Notre QR actuel ne transporte qu'une identité ; il devra transporter une clé
publique et, plus tard, des adresses de transport.

### Secure Scuttlebutt

- Identité = paire **Ed25519** ; journal append-only signé par identité.
- Découverte LAN par UDP, synchronisation opportuniste, gossip multi-sauts.
- Un pair peut demander un contenu qu'il n'a pas ; la demande se propage.
- Source : [guide du protocole](https://ssbc.github.io/scuttlebutt-protocol-guide).

**À retenir** : l'identité cryptographique et le journal signé rendent la falsification
détectable hors ligne, sans serveur.

### Meshtastic (LoRa)

- Diffusion par **managed flooding** avec un **hop limit** décrémenté à chaque relais.
- En-tête de routage **en clair** (16 octets : destination, expéditeur, id de paquet pour
  la déduplication, hop limit, hash de canal), **payload chiffré** : un nœud peut relayer
  sans pouvoir lire.
- Depuis 2.6, les messages directs utilisent un **next-hop** appris, avec repli flooding.
- Le nœud garde ~30 paquets en mémoire pour l'app (store & forward).
- **Pas de forward secrecy**, clé de canal extractible physiquement, confiance « première
  clé entendue » pour les messages directs.
- Sources : [mesh-algo](https://meshtastic.org/docs/overview/mesh-algo),
  [encryption](https://meshtastic.org/docs/overview/encryption/),
  [limitations](https://meshtastic.org/docs/about/overview/encryption/limitations).

**À retenir** : en-tête clair + payload chiffré est le bon compromis pour un relais ; et
un hop limit sans déduplication ne suffit pas (tempêtes).

### SimpleX

- Pas d'identifiant utilisateur : des **files unidirectionnelles** par contact, le serveur
  ne fait que relayer et garde le message jusqu'à réception.
- Les données vivent sur l'appareil ; le serveur n'a pas de comptes.
- Source : [simplex.chat/docs](https://simplex.chat/docs/simplex.html).

**À retenir** : côté serveur, la file par destinataire est un modèle plus sobre que
« tout est lisible par la base ». Supabase + RLS reste notre choix, mais le chiffrement
de bout en bout est ce qui protège réellement le contenu.

## 3. Architecture cible (proposée)

```
        ┌──────────── appareil A ────────────┐
        │ identité Ed25519/X25519 (Keystore) │
        │ journal local signé + déduplication│
        │ courier borné (TTL, hop, quota)    │
        └───┬───────────────┬────────────────┘
            │ BLE / Wi-Fi   │ Internet (Supabase)
            ▼               ▼
        pair B (relais)   file chiffrée par destinataire
            │
            ▼
        pair C (destinataire)
```

1. **Identité** : paire de clés par appareil, clé publique publiée dans `profiles`
   (colonne `public_key`), clé privée dans le Keystore (Android) / non exportable
   (WebCrypto, PWA).
2. **Chiffrement** : session par contact dérivée par X25519 + AEAD (AES-GCM ou
   XChaCha20-Poly1305). Android : `libsignal-android` (X3DH + Double Ratchet) ; PWA :
   WebCrypto. Interopérabilité à tester **avant** d'afficher un cadenas.
3. **Chaîne de transmission** :
   - chaque message porte `id`, `origin`, `createdAt`, `expiresAt`, `hop`, `signature` ;
   - un appareil qui reçoit un message inconnu le **conserve** (courier) et le
     **re-propose** à chaque nouveau pair, tant que TTL et quota le permettent ;
   - déduplication par `(origin, id)` ; jamais de retransmission d'un message déjà remis ;
   - pas de relais d'un message dont la signature est invalide.
4. **En ligne** : Supabase sert de **file de secours** (comme aujourd'hui), mais stocke le
   contenu **chiffré** ; le serveur ne peut plus lire.
5. **Hors ligne, navigateur** : la PWA ne peut pas faire de BLE/Wi-Fi ; elle peut en
   revanche relayer via WebRTC entre onglets/appareils d'un même réseau, avec un serveur
   de signalisation minimal. À décider explicitement (ce n'est pas fait).

## 4. Ce que « comme ARPANET » veut dire concrètement

ARPANET n'avait pas de magie : des nœuds, un en-tête, un TTL, un acquittement, et des
files qui réessaient. Ce qui rend un réseau tolérant aux pannes, c'est :

- **store-and-forward** (on garde, on réémet plus tard) ;
- **déduplication** (un identifiant stable) ;
- **TTL/hop limit** (pas de boucle) ;
- **signature** (on ne relaie pas n'importe quoi) ;
- **bornes** (quota, taille, durée) pour ne pas devenir un dépôt de contenu.

Les quatre premiers existent partiellement dans `:net`. Les deux derniers sont à faire.

## 5. Plan par étapes, avec critères d'acceptation

| Étape | Contenu | Critère d'acceptation |
|---|---|---|
| R1 | Courier persistant borné (Android) : messages reçus non remis conservés chiffrés, TTL 24 h, quota 200 | test unitaire : redémarrage simulé, message toujours proposé ; quota respecté ; rien en clair sur disque |
| R2 | QR enrichi : identité + clé publique + adresses de transport | deux appareils s'ajoutent hors ligne, la conversation et la clé sont créées sans serveur |
| R3 | Signature Ed25519 des enveloppes | une enveloppe modifiée est rejetée ; test JVM + test appareil |
| R4 | E2EE 1-1 Android (libsignal) | deux appareils échangent, le serveur ne voit que du chiffré ; safety number affiché ; test d'interopérabilité |
| R5 | E2EE PWA (WebCrypto) compatible Android | messages croisés Android ↔ PWA déchiffrés ; test automatisé dans le navigateur |
| R6 | Relais multi-sauts réel (3 appareils) | A → B → C sans lien direct ; journal des hops ; pas de doublon |
| R7 | Mode « sans Internet » complet | avion mode : messages, stories et réactions circulent entre 2 appareils à portée |

Tant que R4/R5 ne sont pas terminés **et vérifiés**, l'interface continue d'afficher
« non chiffré de bout en bout ». C'est une règle du projet, pas une option.

## 6. Sources

- bitchat — évaluation de confidentialité :
  <https://raw.githubusercontent.com/permissionlesstech/bitchat/main/docs/privacy-assessment.md>
- bitchat — présentation : <https://bitchat.free/>
- Briar — fonctionnement : <https://briarproject.org/how-it-works>
- Bramble Synchronisation Protocol :
  <https://code.briarproject.org/akwizgran/briar-spec/blob/master/protocols/BSP.md>
- Briar — description des protocoles (BTP, BQP, BRP) :
  <https://www.net.in.tum.de/fileadmin/TUM/NET/NET-2021-05-1/NET-2021-05-1_01.pdf>
- Secure Scuttlebutt — guide du protocole :
  <https://ssbc.github.io/scuttlebutt-protocol-guide>
- Meshtastic — algorithme de maille : <https://meshtastic.org/docs/overview/mesh-algo>
- Meshtastic — chiffrement : <https://meshtastic.org/docs/overview/encryption/>
- Meshtastic — limites du chiffrement :
  <https://meshtastic.org/docs/about/overview/encryption/limitations>
- SimpleX — plateforme : <https://simplex.chat/docs/simplex.html>
