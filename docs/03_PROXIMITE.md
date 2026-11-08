# 03 — Proximité : Bluetooth, Wi-Fi local et relais

Cette couche est réelle : elle utilise les API Android de Bluetooth Low Energy et de
découverte de services réseau. Elle **n'a pas pu être testée sur matériel** dans
l'environnement de développement (aucune radio, aucun second appareil). Le code est livré
avec un protocole documenté et un protocole de test manuel
(`docs/06_TESTS_ET_LIVRAISON.md` §3).

## 1. Enveloppe réseau

Unité de transport : `MessageEnvelope` (`:core`), indépendante du média.

| Champ | Rôle |
|---|---|
| `messageId` | identifiant unique, sert à la déduplication et à la réassemblage |
| `conversationId` | conversation d'origine |
| `senderId` / `recipientId` | extrémités ; `recipientId = null` = diffusion de groupe |
| `createdAt` / `expiresAt` | fenêtre de validité (TTL) |
| `hopCount` | nombre de relais déjà traversés |
| `protocolVersion` | version du protocole, vérifiée à l'acceptation |
| `payload` | contenu **opaque** pour le réseau |

Taille maximale : **64 Kio** par enveloppe. Les transports ne voient jamais autre chose
que cette structure ; le relais ne lit jamais `payload`.

## 2. Frames de transport

BLE et TCP ont des tailles de trame différentes ; un codec commun les normalise :

```
TOMATE1|<messageId>|<index>/<total>|<base64(chunk)>
```

- `MAX_ENVELOPE_BYTES = 64 Kio`, `MAX_FRAMES = 512`.
- Décodage strict : préfixe, nombre de parties, index dans les bornes, base64 valide,
  taille finale bornée. Toute frame invalide est rejetée silencieusement (jamais
  d'exception propagée au transport).
- La réassemblage se fait par `messageId` : les fragments peuvent arriver dans le désordre
  ou être dupliqués sans corrompre le résultat.

Couvert par 4 tests unitaires JVM (`EnvelopeCodecTest`).

## 3. Bluetooth Low Energy

| Élément | Valeur |
|---|---|
| Service UUID | `7f0e1c6a-2f3b-4c1e-9c1a-8a1f5c0d9001` |
| Caractéristique d'écriture | `7f0e1c6a-2f3b-4c1e-9c1a-8a1f5c0d9002` |
| Rôle | **serveur GATT et client GATT** dans la même app |
| Publicité | UUID de service + jeton local de 8 caractères en `serviceData` |
| Fragmentation | selon le MTU négocié, via `EnvelopeCodec` |

Fonctionnement :

1. Chaque appareil ouvre un serveur GATT et publie son service.
2. Il scanne en parallèle les appareils annonçant le même service.
3. À la réception d'une enveloppe complète, `RelayEngine.accept` décide : livrer, relayer
   ou rejeter.
4. Le jeton de `serviceData` permet d'identifier un pair sans appairage système.

Contraintes assumées : débit faible (adapté au texte), portée de quelques mètres,
nécessite `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (API 31+).

## 4. Wi-Fi local

| Élément | Valeur |
|---|---|
| Découverte | NSD / DNS-SD, type `_tomate._tcp.` |
| Transport | TCP, un socket par transfert |
| Port | éphémère (`ServerSocket(0)`), annoncé par NSD |
| Permissions | `NEARBY_WIFI_DEVICES` (API 33+), sinon localisation |

Pourquoi NSD + TCP plutôt que Wi-Fi Direct : Wi-Fi Direct exige une négociation de groupe,
des permissions supplémentaires et un comportement très variable selon les constructeurs.
NSD fonctionne sur tout appareil Android 8+ connecté au même réseau, sans appairage.
L'appairage est implicite : le service est annoncé, l'utilisateur choisit le pair.

## 5. Moteur de relais

`RelayEngine` applique une politique stricte, testée unitairement (11 tests) :

| Règle | Comportement |
|---|---|
| Version de protocole inconnue | rejet `UnsupportedVersion` |
| Enveloppe malformée (id ou payload vide) | rejet `Malformed` |
| Payload > 64 Kio | rejet `TooLarge` |
| `hopCount > maxHops` | rejet `HopLimitExceeded` |
| Enveloppe expirée | rejet `Expired` |
| `messageId` déjà vu | rejet `Duplicate` (protection anti-boucle et anti-tempête) |
| Destinataire = appareil local | `Deliver`, jamais relayé |
| Relais désactivé par l'utilisateur | rejet `ForwardingDisabled` |
| Quota de file atteint | rejet `QuotaExceeded` |

Décisions et garanties :

- **Rien n'est écrit sur disque.** La file de relais est en mémoire (`InMemoryRelayStore`,
  200 entrées) : un relais ne peut ni conserver ni lire le contenu après redémarrage.
- **L'horloge est injectable.** La déduplication et l'expiration utilisent l'horloge du
  moteur, pas l'horloge murale : les tests sont déterministes et un appareil dont l'horloge
  dérive ne casse pas la politique.
- **TTL borné** (`MessageEnvelope.DEFAULT_TTL_MS`, plafond 24 h) et `hopCount` incrémenté à
  chaque transmission réelle (`toForward`, `recordForwarded`).
- **Opt-out utilisateur** dans Réglages : aucune retransmission si désactivé.

## 6. Livraison best-effort

Aucune garantie de livraison n'est promise : proximité = opportuniste. L'app affiche
l'état réel (envoyé / en attente / échec) et ne simule jamais un succès. Quand le réseau
Internet revient, la file d'attente Supabase prend le relais.

## 7. Ce qu'il reste à faire

| Point | Priorité |
|---|---|
| Test matériel BLE (2 appareils) | haute — protocole fourni |
| Test matériel Wi-Fi (2 appareils) | haute |
| Test relais 3 appareils | haute |
| Persistance chiffrée de la file de relais | moyenne (contredit la promesse actuelle, à décider explicitement) |
| Compression des payloads texte | basse |
| Sélection intelligente du média (BLE vs Wi-Fi) | moyenne |
