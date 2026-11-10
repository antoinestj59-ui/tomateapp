# STATUS — TOMATE 0.4.0

Dernière vérification : **10 novembre 2026** (bac à sable cloud, JDK Temurin 21.0.13, Gradle 9.9.0, AGP 9.5.1) et
contrôle syntaxique Node pour la PWA.

Ce fichier décrit **uniquement ce qui a été exécuté**. Le non-testé est déclaré comme tel.

## 1. Android — build et tests

| Commande | Résultat |
|---|---|
| `:app:assembleDebug` | **BUILD SUCCESSFUL**, exit 0 |
| APK | `artifacts/tomate-0.3.0-debug.apk` — 69 Mo (WebRTC pour 4 ABI), `versionCode 12` |
| `:data:testDebugUnitTest` | **BUILD SUCCESSFUL**, 29 tests, 0 échec (dont 7 sondage/vote/événement, 7 signalisation d'appel) |
| `:net:testDebugUnitTest` | **BUILD SUCCESSFUL**, 15 tests, 0 échec |
| `:data:assembleDebugAndroidTest` | compilé, non exécuté (appareil requis) |

## 2. PWA — build et tests réels

| Élément | Valeur |
|---|---|
| Code | `web/` (sans build), `sites.web.json` |
| Aperçu publié | https://site-1b6f0f4eb331483ca0b74abd4600dd43.freebuff.page |
| Version | `0.1.8-web` |
| Auth anonyme | `POST /auth/v1/signup` → **HTTP 200** |
| Publication / réaction / commentaire | écrits puis **vérifiés côté serveur** |
| Messagerie | message PWA → second compte **HTTP 201 vérifié**, réponse → PWA affichée |
| Message écrit hors ligne | mis en file, parti à la reconnexion, **reçu par le second compte** |
| Hors ligne simulé | publication mise en file, aucune fausse erreur |
| Reconnexion | file rejouée, **2 publications sur le serveur**, file à 0 |
| Service worker | actif, 12 fichiers précachés, manifeste `application/manifest+json` |
| Météo | Open-Meteo réel (Lyon), cache daté |
| Installation | **non testée** : `beforeinstallprompt` non déclenché en navigateur de test |
| Rechargement sans réseau | **non conclu** : le proxy d'aperçu suspend au lieu d'échouer |
| Bluetooth réel | **non testé** : aucun appareil à portée |
| Messagerie | **testée dans les deux sens** : message PWA reçu par le second compte, réponse reçue par la PWA |

Détail complet : [`docs/07_PWA.md`](docs/07_PWA.md).

### RLS et messagerie — vérifications de cette itération

| Test | Résultat |
|---|---|
| `tooling/rls_smoke_test.sh` (deux comptes) | **10 assertions PASS, 0 FAIL** : profils, usurpation d'identifiant, publication privée, blocage, conversation, messages, non-accès du compte B |
| Message PWA → second compte `tomate_peer` | **HTTP 201**, relu par l'autre compte |
| Réponse du second compte → PWA | affichée dans la conversation |
| Message rédigé hors ligne puis reconnexion | parti tout seul, **reçu par le second compte** |


## 2 bis. Stories — vérifié en ligne le 9 novembre 2026

Migration `supabase/migrations/0004_stories.sql` appliquée par vous dans le SQL Editor.

| Test | Résultat |
|---|---|
| Création par le compte A | **HTTP 201** |
| Visibilité par le compte B | oui, `viewed_by_me = false` |
| B marque la story vue | **201**, puis `viewed_by_me = true` |
| A voit la liste des vues | oui (identifiant du lecteur, horodatage) |
| B tente de créer une story au nom de A | **403** (RLS `stories_insert_self`) |
| B tente de supprimer la story de A | 204 sans effet : la story reste |
| Story expirant en 70 s | visible avant, **absente de `stories_feed` après 80 s** |
| Création avec une date déjà expirée | **403** |
| Rejeu de la même vue | 201, **une seule ligne** en base |
| D falsifie `viewer_id` (vue au nom de C) | **403** |
| PWA : story écrite réseau coupé | 1 opération en file, badge « en attente » |
| PWA : reconnexion | envoi automatique, story **présente sur le serveur**, badge effacé |
| PWA : suppression par l'auteur | story retirée du serveur |
| Visibilité PWA → autre compte (`story_c`) | story de `web_story1` lue et marquée vue (**201**) |

Android : Room v2 (migration 1→2), `StoryRepository`, kinds `story_insert` / `story_view` /
`story_delete`, bandeau, visionneuse et composeur — **compile et tests unitaires PASS**, mais
**non exécuté sur appareil** (aucun appareil disponible ici).

## 2 ter. Réseau social 0.1.5 — vérifié le 9 novembre 2026

| Fonction | État vérifié |
|---|---|
| Stories photo/vidéo | **code livré**, envoi réel **bloqué** tant que `0005_media_reseau.sql` n'est pas exécutée (le message d'erreur le dit). Photo compressée côté client (1440 px, JPEG 82) et aperçu testés dans le navigateur. |
| Ajout d'ami par QR (PWA) | **vérifié** : QR généré (`tomato://add?u=…`), lien collé, conversation créée avec le bon pair, message envoyé. |
| Ajout d'ami hors ligne | **vérifié en code** : la conversation est écrite localement puis mise en file ; l'envoi réel a été constaté en ligne. |
| Flammes 🔥 | **vérifié** : conversation avec messages de deux comptes sur deux jours → `🔥 2` affiché dans la liste et l'en-tête. |
| Chaîne de messages entre deux comptes | **vérifiée** : conversation créée par M2 (**201**), membre ajouté (**201**), deux messages par compte, l'autre compte lit les quatre. |
| Scanner caméra | **PWA uniquement** (API `BarcodeDetector`) ; non testé ici : le navigateur cloud n'a pas de caméra. Android : QR affiché + code collé, pas de scan caméra. |
| Chiffrement de bout en bout | **non implémenté** — étude et plan dans [`docs/08_RESEAU_ET_CHIFFREMENT.md`](docs/08_RESEAU_ET_CHIFFREMENT.md). |

## 2 quater. Itération 0.1.6 — vérifié le 9 novembre 2026

| Fonction | Preuve réelle |
|---|---|
| Groupe : publication depuis la PWA | **oui** : composeur dans la page du groupe, message envoyé, `feed` serveur renvoie `community_id` + `visibility=Community`, file d'attente à 0 |
| Groupe : découverte depuis un autre appareil | **oui, par API** : compte A crée `Jardin test` (201), compte B le voit, le rejoint (201) et publie dedans (201) ; A lit le message |
| Groupe : adhésion du créateur | **corrigée** : la PWA n'envoie plus une seconde adhésion `Owner` (403) ; le trigger serveur fait le travail |
| Groupe : liste des membres | affichée avec rôle (propriétaire marqué) et statut |
| Foule : lien d'invitation | bouton « Inviter » qui copie `…/#/community/<id>` |
| Publication avec photo/vidéo | **code PWA + Android**, envoi **refusé proprement** tant que `0006_social.sql` n'est pas appliquée : « Stockage photo/vidéo non activé… », aucune publication créée, média conservé pour réessayer |
| Brouillon de publication | **vérifié** : texte écrit, page rechargée, brouillon restauré (localStorage) |
| Recherche globale | **vérifiée** : « Jardin » → 2 groupes ; « groupe » → la publication ; « web_qr1 » → la personne, et un clic crée la conversation (3 opérations synchronisées) |
| Recherche hors ligne | limitée au cache, et **annoncée comme telle** |
| Nouvelle interface | palette tomate, marque SVG, navigation flottante à 5 entrées, cartes de publication avec média, thème sombre, `prefers-reduced-motion`, focus visible |
| Android | `:app:assembleDebug` **BUILD SUCCESSFUL**, tests unitaires core/data/net **PASS**, Room **v4** (migration 3→4, `4.json` généré), publication avec photo/vidéo compilée |

Non vérifié dans cette itération : l'envoi réel d'une photo de story ou de publication (migrations
`0005`/`0006` à exécuter), l'upload Android (aucun appareil), l'adhésion à un groupe privé
(UI d'approbation absente), le scan caméra Android.

## 3. Correctifs de cette itération

| Bug constaté | Cause | Correction |
|---|---|---|
| « Se déconnecter » ne faisait rien de visible | la base était vidée mais l'écran d'onboarding ne revenait jamais | `needsOnboarding` dérivé aussi de l'absence de profil |
| Faux « Cette action entre en conflit avec des données existantes » à la publication | inserts non idempotents : rejeu d'un contenu déjà présent → HTTP 409 | `resolution=merge-duplicates` sur les identifiants générés côté client (Android **et** PWA) |
| Commentaire web bloqué sur « En attente » après envoi | l'état n'était pas remis à jour après le vidage de la file | `markSynced` étendu aux commentaires + rechargement depuis IndexedDB |
| « Commu… » tronqué dans la barre du bas | libellé trop large pour 5 entrées | taille de police ajustée, libellé sur une ligne |
| Erreur « réseau indisponible » affichée hors ligne | le message d'échec était traité comme une erreur | hors ligne = simple notice « en attente », pas de bannière d'erreur |
| Création de conversation Android inutilisable | `created_by` et les membres n'étaient jamais envoyés | `ConversationDto.createdBy`, ajout des membres et des kinds `conversation_member_add*` |
| Ajout de membre / adhésion / réaction → **403** | `resolution=merge-duplicates` exige une politique UPDATE absente sur ces tables | `ignore-duplicates` (Android et PWA), insert simple pour `conversations` |
| Message web → **400** | le champ local `pending` partait sur le réseau | payload limité aux colonnes serveur |
| Opération refusée définitivement affichée « en attente » | la file supprimait l'opération sans mettre à jour le cache | `markFailed` + réconciliation au démarrage, badge **Non envoyé** |
| PWA servant du JS périmé après mise à jour | précache et fetch en cache-first figeaient une version | `cache: reload` à l'installation, scripts/styles en **réseau d'abord** (repli cache) |
| Profil orphelin après création de compte web | deux `ensureActor()` concurrents (démarrage + onboarding) créaient deux comptes anonymes ; le profil restait lié à celui dont la session n'était pas conservée → **403 sur toutes les écritures** | `ensureActor` sérialisé par une promesse partagée ; vérifié : `session.userId == profile.id` |

## 4. Fonctionnalités annoncées — état

### Android (compilé, partiellement testé)

Onboarding hors ligne, fil, publications, réactions, commentaires, communautés, messagerie
local-first, blocage/signalement, profil, session anonyme + refresh Keystore, BLE/Wi-Fi/relais
(protocole compilé, **non exécuté sur radio**).

### PWA (testée en navigateur)

Compte local/hors ligne, fil, publication, réaction, commentaire, messagerie texte, communautés,
météo, file d'attente, page de permissions, thème, installation (manifeste + SW).

### Absent — déclaré absent

Chiffrement de bout en bout, récupération de compte, suppression de compte serveur, événements,
cartes hors ligne, appels, notifications push, mesh navigateur, médias dans les messages,
groupes de discussion, modération et administration. Le détail par fonction est dans
[`docs/09_MATRICE_FONCTIONNALITES.md`](docs/09_MATRICE_FONCTIONNALITES.md).

## 5. Ce qui n'a pas pu être testé ici

| Test | Raison |
|---|---|
| Tests instrumentés Room (5) | pas d'appareil / `/dev/kvm` |
| BLE / Wi-Fi / relais multi-téléphones | pas de radio |
| Installation PWA | `beforeinstallprompt` non émis dans le navigateur cloud |
| Rechargement totalement hors ligne | proxy d'aperçu suspend la requête |
| Captures d'écran | aucun émulateur, captures navigateur possibles seulement sur demande explicite |

## 6. Décisions notables

| # | Décision | Raison |
|---|---|---|
| 1 | PWA sans framework ni build | déploiement direct, surface minimale, cache simple |
| 2 | Écritures idempotentes par identifiant client | rejeu sûr, plus de faux conflits |
| 3 | Compte local `local-…` aussi dans la PWA | même promesse produit que l'Android |
| 4 | Permissions expliquées et testées sur action | « dire pourquoi » et ne rien simuler |
| 5 | Aucun mesh simulé dans la PWA | un navigateur ne peut pas relayer en arrière-plan |

## 7. Prochaines étapes

1. Tester la PWA sur un vrai téléphone : installation, mode avion, reconnexion.
2. Test matériel BLE/Wi-Fi/relais Android (3 appareils).
3. Récupération de compte (fonction serveur) et suppression de compte.
4. E2EE avec un protocole reconnu.

## 8. Honnêteté de livraison

- Aucun bouton fictif ; chaque action appelle une vraie API ou explique son absence.
- Aucune donnée météo inventée, aucune ancienneté masquée.
- Aucune clé secrète dans le dépôt, l'APK ou la conversation (clé publishable uniquement).
- Aucune publication en production, aucune publication Play Store.

## 2 quinquies. Itération 0.1.7 — vérifié le 9 novembre 2026

Migrations `0005` et `0006` exécutées par le propriétaire du projet. Vérifications **réelles**
contre `omsupwmlncuapikbjumg.supabase.co` :

| Élément | Résultat mesuré |
|---|---|
| Upload photo de publication | `POST /storage/v1/object/posts/…` → **200** |
| Publication avec média | `POST /rest/v1/posts` → **201**, `feed.media_path` renseigné, image publique servie en `image/png` |
| Média de story | upload **200**, story **201**, visible par un **second compte** |
| Membre d'un groupe public | visible par un tiers (rôle `Owner`, statut `Active`) |
| Adhésion à un groupe privé | **`Pending`** accepté ; tentative `Active` **refusée (403)** |
| Publication photo depuis la PWA | parcours réel : sélecteur → compression (JPEG 1 Ko) → upload → fil, image chargée (64×64) |
| Protection du compte | `PUT /auth/v1/user` (e-mail + mot de passe) → **200**, connexion par mot de passe → **même identifiant** `fb9b4773…`, profil `@verifmedia` retrouvé |
| Restauration sur un autre appareil | second stockage navigateur (origine déployée) → « J'ai déjà un compte » → `#/feed` restauré, badge de notifications présent |
| Notifications | table `0007` **non encore créée** : l'écran affiche honnêtement « Notifications pas encore activées : appliquez la migration 0007… » |
| Android | `:app:assembleDebug` **BUILD SUCCESSFUL** (`versionCode 8`), client FCM inclus, plugin Google conditionnel (`google-services.json` absent → build valide) |

Non vérifié, déclaré comme tel : envoi média Android (aucun appareil), affichage d'une
notification push (projet Firebase et fonction Edge non déployés), test A→B→C, appels, E2EE.

### Correctif trouvé par ce test

La connexion par mot de passe ne persistait pas la session : `fetchMyProfile` lisait une session
vide et l'écran annonçait « Ce compte n'a pas encore de profil ». `signInWithPassword` enregistre
désormais la session avant de rendre la main.

## 2 sexies. Itération 0.1.8 — vérifié le 9 novembre 2026

Objectif de cette itération : traiter des défauts concrets plutôt qu'ajouter des écrans.

| Point | Avant | Après | Preuve |
|---|---|---|---|
| Création de conversation (Android) | champ « pseudos séparés par des virgules » | sélecteur de contacts avec recherche, sélection multiple, avatars | `:app:assembleDebug` **BUILD SUCCESSFUL** |
| Création de conversation (PWA) | `prompt()` sur un pseudo exact | sélecteur de contacts (dialog) avec recherche serveur, 1 ou plusieurs personnes | testé sur l'aperçu déployé : 20 profils réels listés |
| Conversation créée depuis la PWA | — | écrite sur le serveur, visible par le destinataire, réponse reçue dans la PWA | conversation `79263ab9…`, message lu par le destinataire, réponse `201` affichée |
| Quitter une conversation | impossible (aucun bouton) | bouton « Quitter », vérifié côté serveur | adhésion supprimée (`conversation_members`), liste vidée |
| Supprimer pour tout le monde | impossible | bouton réservé au créateur + politique `0008` | refus honnête constaté tant que `0008` n'est pas appliquée |
| Code déployé réellement exécuté | l'ancien service worker restait actif : la PWA affichait l'ancien code après déploiement | rechargement automatique quand un nouveau worker prend la main | constaté : cache `tomate-shell-v23` encore actif après un déploiement 0.1.8 |
| Titre de conversation | « Conversation » si le cache était incomplet | la conversation est rechargée avant d'afficher l'en-tête | titre « Contact Picker » affiché après correctif |

Non fait dans cette itération (déclaré, pas simulé) : suppression/quitter une conversation côté
Android (l'API `conversations_delete_creator` existe, l'écran n'a pas encore l'action), appels,
E2EE, modération, événements, sondages, notes, annonces, assistant, refonte visuelle Android.

### Diagnostic des « changements qui n'apparaissent pas »

Deux causes mesurées, corrigées :

1. **Service worker périmé.** Le worker `v23` restait maître de la page après un déploiement ; la
   PWA exécutait l'ancien `app.js` alors que `config.js` annonçait la nouvelle version. Corrigé par
   un rechargement automatique sur `controllerchange` (une seule fois par session) et par
   `registration.update()` au lancement et au retour au premier plan, qui contourne le cache HTTP
   de 24 h du script du service worker. Mesuré sur l'aperçu déployé : cache `v24` → `v25` et
   rechargement automatique (`tomate:shell-reloaded = 1`) sans intervention.
2. **Versions multiples.** Un APK installé peut être antérieur au dépôt : l'écran
   *Profil → À propos* affiche `TOMATE 0.1.8 (build 9)`. Toute capture sans ce numéro correspond à
   une version antérieure.

## 2 septies. Itération 0.1.9 — vérifié le 9 novembre 2026

### Ce qui a été créé

| Élément | Fichier | État |
|---|---|---|
| Pièces jointes de conversation (photo, vidéo, vocal) | `web/js/app.js`, `web/css/app.css` | code déployé, **envoi effectif en attente de la migration 0009** |
| Bucket privé `messages` + colonnes `media_*` + politiques membres | `supabase/migrations/0009_medias_messagerie.sql` | écrit, validé pglast, **non appliqué** (aucune clé `service_role` ici) |
| Export de mes données (profil, publications, stories, messages) | `web/js/app.js` (`exportMyData`) | **testé sur l'aperçu déployé** |
| Quitter / supprimer une conversation (Android) | `MessagingRepository`, `ChatViewModel`, `ChatScreen`, `Daos.kt` | **compilé** (`assembleDebug`), non exécuté faute d'appareil |

### Vérifications réellement exécutées sur l'aperçu déployé

| Action | Résultat observé |
|---|---|
| Version PWA | cache `tomate-shell-v27`, `0.1.9-web` |
| Connexion `verifmedia` par e-mail + mot de passe | session restaurée, même `user.id` |
| Composer de conversation | bouton trombone + champ fichier présents |
| Sélection d'une image PNG (320×200) | aperçu « Photo · 3 Ko · JPG » (compression client 1440 px) |
| Envoi avec pièce jointe, bucket absent | toast « Pièces jointes non activées : appliquez la migration 0009_medias_messagerie.sql. », **aucun message fantôme créé**, texte et aperçu conservés |
| Envoi texte après refonte | message `Texte seul après refonte 0.1.9` affiché, « 1 opération(s) synchronisée(s) » |
| Export des données | « 0 publication(s), 0 story(s), 1 message(s) exportés. » — comptes réels, pas de valeurs inventées |

### Limites de cette itération

- **Le chemin média n'est pas vérifié de bout en bout** : sans la migration 0009, le bucket et les
  colonnes n'existent pas. Le refus est explicite et testé ; l'upload réel, la lecture par un second
  compte et le refus d'un non-membre restent à exécuter après application de 0009.
- Android : `assembleDebug` passe (`versionCode 10`), mais quitter/supprimer une conversation n'est
  pas exécuté sur appareil ; la suppression « pour tout le monde » dépend aussi de la migration 0008.
- Toujours non appliquées par le propriétaire : `0007` (notifications), `0008`, `0009`.
- Non entamé : appels audio/vidéo, E2EE réel, mesh A→B→C, sondages, événements, modération
  administrateur, assistant.

## 2 octies. Itération 0.2.x — sondages, événements, appels (vérifié le 10 novembre 2026)

### Ce qui a été créé

| Élément | Fichier | État |
|---|---|---|
| Protocoles sondage / vote / événement / appel, `CallSession` WebRTC, auto-test de négociation | `web/js/extras.js` | **exécuté en navigateur réel** |
| Bouton sondage, cartes d'événement + RSVP, appels audio/vidéo, fenêtre d'appel | `web/js/app.js`, `web/css/app.css` | **déployé** (0.2.4-web, cache `tomate-shell-v32`) |

Aucune migration nouvelle : ces fonctions réutilisent `messages`, `posts` et `post_reactions` avec des
protocoles préfixés (`[[tomate:poll:v1]]`, `[[tomate:vote:v1]]…`, `[[tomate:event:v1]]`,
`[[tomate:call:v1]]`). Les messages de protocole sont filtrés à l'affichage.

### Vérifications réellement exécutées sur l'aperçu déployé

| Action | Résultat observé |
|---|---|
| Sondage créé dans une conversation (compte `verifmedia`) | carte affichée, question et options lisibles, aucun JSON brut |
| Vote depuis `verifmedia` (option 0) et `verif689a7b` (option 1) | « 1 / 1, 2 votant(s) » : dépouillement dédupliqué par votant |
| Événement « Apéro TOMATE » créé via l'UI | carte datée, lieu, sans marqueur brut |
| RSVP coché / décoché / recoché | ligne `post_reactions kind='participe'` créée, **supprimée réellement** (`[]` côté serveur), recréée ; compteurs 1 → 0 → 1 |
| Bug corrigé : compte du créateur | affichait 0 RSVP, initialisé à 1 (`{count: 1, mine: true}`) |
| Appel audio : offre | **erreur `23514` réelle** : le SDP dépasse les 4000 caractères de `messages.body`. Corrigé par découpage/réassemblage (`packSignal`/`collectSignals`) |
| Appel audio après correctif, deux comptes | offre envoyée, second compte reçoit « Appel audio entrant » (Refuser / Répondre), réponse appliquée par l'appelant (`signalingState: stable`), pistes distantes reçues |
| Établissement ICE entre les deux contextes du navigateur cloud | **échoue** (`ice: disconnected` → `connection: failed`) : les deux onglets sortent par le même NAT, sans serveur TURN. l'appel se termine. Le message d'échec ajouté ensuite (« Appel échoué : la liaison directe n'a pas abouti (réseau restrictif ou NAT) ») a été vérifié séparément par injection de l'état `failed` sur la connexion : toast affiché, fenêtre fermée |
| Chemin média complet, deux pairs, flux synthétiques (canvas + oscillateur) | `loopbackNegotiationTest({ media: true })` → **`ok: true` en 1 999 ms**, chaque pair reçoit `audio:live` + `video:live` |

### Limites de cette itération

- **Aucun appel réel entre deux appareils n'a été vérifié.** Ce qui est prouvé : la signalisation
  (offre, sonnerie, réponse, candidats) et le chemin média (pistes ajoutées, négociées, reçues).
  Ce qui ne l'est pas : l'établissement ICE hors NAT partagé, donc l'audio audible. Aucun TURN
  n'est configuré ; sur deux réseaux distincts l'appel peut échouer de la même façon.
- Le navigateur cloud n'a **ni micro ni caméra** : les tests média utilisent des flux synthétiques
  (`canvas.captureStream` + oscillateur), pas un périphérique réel.
- **Android n'avait pas ces fonctions au moment de cette itération** : sondages, événements et
  appels existaient uniquement dans la PWA. Sondages et événements ont depuis été portés (voir
  § 2 nonies) ; **les appels restent PWA uniquement**.
- Toujours non appliquées par le propriétaire : `0007` (notifications), `0008`, `0009` (médias).
- Non entamé : refonte visuelle Android, E2EE réel, mesh A→B→C, modération administrateur, assistant.

## 2 nonies. Itération 0.2.5 — relais TURN, diagnostic ICE et parité Android (vérifié le 10 novembre 2026)

### Pourquoi

Le § 2 octies laissait deux trous : un appel ne pouvait pas aboutir derrière un NAT restrictif
(aucun TURN), et Android n'affichait ni sondages ni événements. Les deux sont traités ici, avec
ce qui est réellement prouvé et ce qui ne l'est pas.

### PWA — relais TURN configurable (`web/js/extras.js`, `web/js/app.js`)

| Élément | Vérification |
|---|---|
| Réglages TURN (adresse, identifiant, mot de passe) dans **Paramètres → Appels : relais TURN** | carte rendue, formulaires relus depuis `localStorage`, adresse refusée si elle ne commence pas par `turn:`/`turns:` |
| Enregistrement d'un relais | `localStorage['tomate.turn.v1']` contient l'adresse et les identifiants, diagnostic lancé automatiquement |
| Diagnostic sans relais (aperçu `0.2.5-web`) | **« 1 local · 1 par STUN en 0.1 s — STUN répond ; ajoutez un TURN pour les réseaux restrictifs. »** |
| Diagnostic avec un relais **hors service** (`turn:openrelay.metered.ca:80`) | **« 1 local · 1 par STUN en 8.0 s — TURN configuré mais aucun relais obtenu : vérifiez l'adresse, les identifiants et le réseau. »** — aucun candidat `relay`, l'app le dit |
| Liste ICE réellement transmise aux appels | `iceServers()` renvoie STUN + TURN enregistré ; les deux `CallSession` (appel sortant et entrant) l'utilisent |

### Android 0.2.0 — sondages et événements (`versionCode 11`)

| Élément | Vérification |
|---|---|
| Codec partagé `data/extras/ConversationExtras.kt` | mêmes préfixes que le web (`[[tomate:poll:v1]]`, `[[tomate:vote:v1]]`, `[[tomate:event:v1]]`) |
| Tests unitaires `ConversationExtrasTest` | **7 tests, 0 échec** : aller-retour sondage, options vides écartées, vote avec identifiant contenant `:`, dépouillement « un votant = une voix, dernier vote gagnant », événement avec date invalide refusé, relecture de chaînes produites par le client web |
| Création de sondage (conversation) | dialogue question + 2 à 3 options, envoi par la même file locale que les messages ; aperçu de conversation « 📊 question » |
| Vote | message de protocole, filtré de l'affichage ; barres et pourcentages recalculés depuis les messages reçus |
| Création d'événement (fil) | bouton « Événement », date `AAAA-MM-JJ` + heure `HH:MM` converties en instant ISO du fuseau de l'appareil |
| Affichage d'un événement | carte titre / date lisible / lieu / détails, à la place du corps brut, dans le fil et le détail |
| « Je participe » (détail d'un événement) | `post_reactions kind='participe'` ; hors ligne, l'opération part dans la file locale (`participation_add` / `participation_remove`) |
| Compilation | `:app:assembleDebug` exit 0 → `artifacts/tomate-0.2.0-debug.apk` |

### Protocole Android vérifié contre le vrai serveur (REST, compte `verifmedia`)

Publication `49deae70-1bbe-4310-a0bb-ed374c99658c` (« Apéro TOMATE ») :

| Action | Résultat |
|---|---|
| `GET post_reactions?kind=eq.participe` | `[]` avant, `[{user_id: fb9b4773…}]` après insertion |
| `POST kind=participe` | **201** (RLS l'autorise, clé PK `(post_id, user_id, kind)`) |
| `DELETE …&kind=eq.participe` | **204**, relecture `[]` — suppression réelle |
| `tomate` + `participe` du même compte, puis suppression ciblée `kind=eq.participe` | reste `[{kind: tomate}]` : le filtre par kind n'efface pas l'autre réaction |
| Nettoyage | état serveur rendu à `[]` |

### Limites de cette itération

- **Aucun relais TURN opérationnel n'a pu être testé** : le relais public d'essai utilisé
  (`openrelay.metered.ca`) ne renvoie plus de candidat `relay`. Le diagnostic prouve donc qu'il
  détecte un relais **qui ne marche pas**, pas encore un relais qui marche. Aucun appel réel entre
  deux réseaux n'a été établi.
- Le navigateur cloud ne collecte **qu'un candidat `host` et un `srflx`** : suffisant pour un
  appel LAN, insuffisant pour deux réseaux restrictifs.
- **Android n'avait pas d'appels à cette itération** : la signalisation WebRTC native a été
  écrite depuis (voir § 2 decies). Un appel PWA ne pouvait alors rien déclencher sur Android.
- **Aucun appareil Android ni émulateur** : l'APK est compilé, pas exécuté. Les écrans sondage et
  événement ne sont donc **pas** vérifiés visuellement.
- Toujours non appliquées par le propriétaire : `0007`, `0008`, `0009`.

## 2 decies. Itération 0.3.0 — appels natifs Android (vérifié le 10 novembre 2026)

### Ce qui est en place

| Élément | Fichier | État |
|---|---|---|
| Codec de signalisation partagé (découpage base64, `id\|index\|total\|morceau`, 3000 caractères) | `data/.../extras/CallCodec.kt` | testé (7 tests) |
| Moteur WebRTC : offre, réponse, candidats, fin, micro, caméra, haut-parleur | `app/.../call/CallEngine.kt` | compilé |
| Relais à la conversation : relecture toutes les 2 s, signaux déjà traités mémorisés | `app/.../call/CallController.kt` | compilé |
| Boutons « Appel audio » / « Appel vidéo » (permission demandée seulement au moment d'appeler) | `ChatScreen.kt` | compilé |
| Fenêtre d'appel : sonnerie, accepter/refuser, micro, caméra, haut-parleur, raccrocher, échec explicité | `ui/chat/CallOverlay.kt` | compilé |
| Réglages « Appels : relais TURN » (adresse, identifiant, mot de passe, stockés sur l'appareil) | `SettingsScreen.kt`, `SettingsStore.kt` | compilé |
| Permissions `RECORD_AUDIO`, `CAMERA`, `MODIFY_AUDIO_SETTINGS` (features déclarées non obligatoires) | `AndroidManifest.xml` | compilé |
| Bibliothèque `io.github.webrtc-sdk:android:137.7151.05` | `libs.versions.toml` | 4 ABI dans l'APK |

### Vérifications réellement effectuées

| Test | Résultat |
|---|---|
| `CallCodecTest` (JVM) | **7 tests, 0 échec** : petit signal en un morceau, SDP de 5 Ko découpé en morceaux < 4000 caractères, paquet incomplet ignoré, ses propres messages ignorés, morceau produit par la PWA relu par Android (candidat ICE compris), signal Android relu comme le ferait le web (accents UTF-8 préservés) |
| Suite `:data` complète | **29 tests, 0 échec** |
| Suite `:net` complète | **15 tests, 0 échec** |
| `:app:assembleDebug` | **BUILD SUCCESSFUL**, `artifacts/tomate-0.3.0-debug.apk`, `libjingle_peerconnection_so.so` présent pour arm64-v8a, armeabi-v7a, x86, x86_64 |
| Interopérabilité réelle dans le navigateur, sur l'aperçu déployé | offre construite **au format exact d'Android** (base64 + `id\|index\|total\|morceau`), relue par le `collectSignals` de la PWA, `CallSession` web répond, la réponse et **2 candidats ICE** reviennent au format Android et sont appliqués par un `RTCPeerConnection` brut : **`connectionState: connected` des deux côtés, `audio:live` reçu des deux côtés** |

### Limites de cette itération

- **Le moteur Kotlin n'a jamais tourné.** Aucun appareil Android ni émulateur n'est disponible
  (`/dev/kvm` appartient à `root:kvm`, l'utilisateur du bac à sable n'y a pas accès ; aucun
  system-image installé). Ce qui est prouvé : le format de signalisation et la négociation
  (via un pair WebRTC du navigateur tenant le rôle d'Android), la compilation, la présence des
  bibliothèques natives. Ce qui ne l'est pas : capture micro/caméra réelle, routage audio,
  rendu vidéo Compose, comportement du moteur sur un téléphone.
- Le test d'interopérabilité s'est déroulé **dans un seul navigateur**, deux contextes sur le même
  réseau : il prouve la négociation et l'échange de médias, pas un appel entre deux réseaux
  distincts (il faudrait un TURN opérationnel, toujours indisponible ici).
- Les appels ne sonnent **que dans la conversation ouverte** : c'est la même limite que la PWA
  (surveillance démarrée à l'ouverture du fil), pas une notification d'appel système.
- L'APK pèse 69 Mo parce qu'il embarque les quatre ABI ; un découpage par ABI n'a pas été fait.
- Toujours non appliquées par le propriétaire : `0007`, `0008`, `0009`.

## 2 undecies. Itération 0.3.1 — file d'attente bloquée, erreurs collantes, libellés coupés (10 novembre 2026)

### Symptômes constatés sur l'appareil (captures du propriétaire)

1. Accueil : bandeau rouge « Cette action entre en conflit avec des données existantes. » qui ne
   part jamais, plus « 20 opération(s) en attente de synchronisation. » qui ne descend jamais à 0.
2. Communauté « Les SLP » : bouton « Rejoindre » toujours affiché, même bandeau rouge, et
   « Les publications sont réservées aux membres actifs » — l'adhésion ne se fait pas.
3. Mise en page : le bouton « Événement » se cassait en « Év / én » et l'onglet « Communautés » en
   « Communau / tés ».

### Causes trouvées dans le code (pas des suppositions)

| Cause | Où | Effet |
|---|---|---|
| Le compteur lisait **toutes** les lignes de la file, y compris celles **définitivement refusées** (épuisement des 8 tentatives) | `OutboxDao.observeCount()` | le nombre ne pouvait plus redescendre : 20 pour toujours |
| Un refus définitif restait **invisible et sans action** : aucune trace, aucun moyen de réessayer ou d'abandonner | `SyncEngine` | l'utilisateur ne pouvait rien corriger |
| Plusieurs rejeux n'étaient **pas idempotents** : `story_insert`, `conversation_create`, `community_create`, `report_create`, `message_send`, `comment_insert`, `post_insert` faisaient un simple insert ; `community_join`/`block_create` faisaient un merge sans cible | `SyncEngine.dispatch` | une requête arrivée au serveur mais dont la réponse se perdait produisait un doublon (23505) → « conflit de données », puis blocage |
| Un **403** (refus de politique RLS) était traduit en « session expirée » | `SupabaseApi.mapStatusToException` | rafraîchissement de jeton inutile, vraie cause masquée, retours en boucle |
| Les bandeaux d'erreur n'avaient **aucune croix** et l'erreur n'était pas remise à zéro avant une nouvelle action | `TomateInlineNotice`, `HomeViewModel`, `CommunityDetailViewModel` | une erreur d'il y a dix minutes restait affichée par-dessus l'écran courant |
| Boutons dans une `Row` avec `Spacer(weight)` ; libellé d'onglet au style par défaut | `HomeScreen`, `TomateApp` | « Év / én », « Communau / tés » |

### Corrections

- `OutboxDao` : le compteur « en attente » ne compte que les opérations **vivantes** ; nouvelles
  requêtes `observeDeadCount`, `observeLastFailure`, `dead()`, `reviveDead()`, `discardDead()`.
- `SyncEngine` : rejeu idempotent de **toutes** les écritures (`ignore-duplicates` avec la clé
  d'unicité réelle de la table : `id`, `post_id,user_id,kind`, `community_id,user_id`,
  `blocker_id,blocked_id`) ; `retryFailed()` et `discardFailed()`.
- `OutboxRemote` (nouvelle interface) implémentée par `SupabaseApi` : la stratégie de rejeu est
  désormais **testable** en JVM, ce qui n'était pas le cas avant.
- `AppError.Forbidden` : un 403 n'est plus une session expirée, et le message le dit.
- Message de conflit reformulé : « Cette action existe déjà sur le serveur : elle a été ignorée,
  rien n'a été dupliqué. »
- Accueil : bandeau d'erreur avec croix ; nouveau bandeau « N opération(s) refusée(s) par le
  serveur » avec la dernière raison et deux actions, **Réessayer** / **Abandonner**.
- Communauté : erreur remise à zéro au début de chaque action, croix sur le bandeau, message
  adapté à l'état réel (`Pending`, `Rejected`, `Banned`), vidage de la file après une adhésion.
- `ComposerActions` et `NavigationTabLabel` extraits pour être rendus et testés : `FlowRow` pour
  Photo / Vidéo / Événement ; onglets sur une ligne en 9 sp.

### Vérifications réellement exécutées

| Test | Résultat |
|---|---|
| `SyncEngineTest` (nouveau, 8 tests JVM) | **8/8** : rejeu d'une story → `stories` + `ignore-duplicates` + `id` ; rejeu d'une adhésion → `community_members` + `community_id,user_id`, jamais de merge ; rejeu d'une réaction → clé réelle ; refus définitif → **hors du compteur mais conservé** ; panne passagère → replanifiée ; `retryFailed()` renvoie l'opération ; `discardFailed()` l'efface ; kind inconnu refusé |
| Suites complètes `:data`, `:net`, `:app` | **54 tests, 0 échec, 0 ignoré** (dont 2 tests de rendu) |
| `UiScreenshotTest` (nouveau, Robolectric + Compose, 360 dp) | rendu réel en pixels ; assertions : « Communautés » et « Messages » ont la même hauteur (donc pas de retour à la ligne), « Événement » et « Photo » aussi |
| Captures produites puis **regardées** | `artifacts/ui-check/navigation.png` (« Communautés » entier, une ligne) et `artifacts/ui-check/composer.png` (« Photo », « Vidéo », « Événement » entiers) |
| Requêtes réelles sur le vrai serveur (compte `verifmedia`) | adhésion à « Les SLP » **201**, publication dans la communauté **201**, réaction `tomate` **201** — les lignes de test ont ensuite été **supprimées** (204) et l'état du serveur remis à 2 membres |
| `:app:assembleDebug` | **BUILD SUCCESSFUL**, `artifacts/tomate-0.3.1-debug.apk` (`versionCode 13`) |

### Limites de cette itération

- **Toujours aucun appareil ni émulateur** (`/dev/kvm` inaccessible, pas de `sudo`) : les
  corrections sont compilées et testées en JVM, les deux zones réécrites sont **rendues en image**
  et regardées, mais l'app complète n'a pas été exécutée sur un téléphone.
- Le **conflit exact** qui a rempli la file de l'appareil n'a pas pu être lu : la base Room de
  l'appareil est inaccessible d'ici. Ce qui est corrigé, c'est la classe entière de causes
  (rejeu non idempotent, refus définitif compté, erreur collante) — pas une ligne précise
  identifiée sur le téléphone.
- Les 20 opérations déjà présentes sur l'appareil apparaîtront après mise à jour comme
  « opération(s) refusée(s) » avec leur raison : **Réessayer** les rejoue maintenant sans doublon,
  **Abandonner** les efface. Si elles échouent encore, la raison exacte sera affichée à l'écran.
- Toujours non appliquées par le propriétaire : `0007`, `0008`, `0009` (pas de clé `service_role`).

## 2 duodecies. Itération 0.4.0 — refonte expressive, réponses, réactions, édition (10 novembre 2026)

Branche : `tomate/mission-refonte-produit`.

### Refonte graphique (code réel, pas une maquette)

| Élément | Fichier | État |
|---|---|---|
| Palette expressive (conteneurs `surfaceContainer*`, primaire tomate recalibré, statuts conservés) | `core/.../theme/Color.kt` | compilé |
| Logo tomate vectoriel (Canvas, sans dépendance d'image) + avatars de repli | `core/.../components/TomateLogo.kt` (nouveau) | compilé |
| Navigation expressive : icônes remplies/contours selon la sélection | `TomateDestination.kt`, `TomateApp.kt` | compilé |
| Cartes expressives : `PostCard`, `ConversationRow` en `surfaceContainer` + `extraLarge`, hiérarchie resserrée | `SharedComponents.kt` | compilé |
| Bulles expressives : asymétriques, citation de réponse, état de livraison honnête | `SharedComponents.kt` | compilé |
| Logo dans la barre d'accueil | `HomeScreen.kt` | compilé |
| PWA : mêmes formes/couleurs, nouveaux styles citation/outils/chips/bandeau de réponse | `web/css/app.css` | `node --check` OK |

### Fonctionnel (sans migration pour l'existant, fondation + repli honnête pour le reste)

| Fonction | Base servie | État |
|---|---|---|
| Répondre à un message (citation affichée, `reply_to_id` envoyé) | colonnes existantes | Android + PWA, file idempotente |
| Recherche locale dans la conversation | aucune (filtre local) | Android + PWA |
| Modifier une publication (+ détail) | `posts_update_author` existante | Android + PWA (`post_update` rejouée par les deux files) |
| Renommer un groupe | `conversations_update_member` existante | Android + PWA (`conversation_rename` rejouée) |
| Réactions aux messages (🍅 ❤️ 👍…) | **migration `0010_message_reactions.sql` à appliquer** | code livré, repli explicite « Réactions non activées : appliquez la migration 0010… » tant qu'elle manque, file `message_reaction_add/remove` prête |
| `ALL_IN_ONE.sql` inclut désormais `0007`→`0010` (en-tête corrigé) | — | fichier à jour |

### Vérifications réellement exécutées

| Test | Résultat |
|---|---|
| `:core` + `:data` + `:net` + `:app` (tests unitaires JVM) | **57 tests, 0 échec, 0 erreur** (dont `SyncEngineTest` passé de 8 à 11 : `post_update`, `conversation_rename`, `message_reaction_add`) |
| `:app:testDebugUnitTest` (dont `UiScreenshotTest` Robolectric) | **BUILD SUCCESSFUL** |
| `:app:assembleDebug` | **BUILD SUCCESSFUL**, `artifacts/tomate-0.4.0-debug.apk` (71 Mo, `versionCode 14`) |
| `node --check web/js/app.js web/js/queue.js` | OK |

### Limites de cette itération (déclarées, pas simulées)

- **Aucun appareil ni émulateur** : l'APK 0.4.0 est compilé, pas exécuté sur téléphone ; les écrans
  réécrits (bulles, cartes, dialogue d'édition) ne sont pas vérifiés visuellement ici.
- Les **réactions aux messages** exigent la migration `0010` : sans elle, l'app l'annonce au lieu
  d'inventer un état.
- La PWA n'a pas été rejouée contre le serveur ici (aucune clé dans cet environnement) : les
  nouveaux chemins (`post_update`, `conversation_rename`, `message_reactions`) suivent les mêmes
  conventions que les chemins déjà vérifiés (`Prefer`, `on_conflict`, file rejouable).
- Toujours non entamé : E2EE réel, relais A→B→C sur trois téléphones, modération administrateur,
  assistant, appels de groupe.

### Rejeu réel contre le serveur (nuit du 10 novembre 2026, `tooling/verify_040_reactions.sh`)

Après application de `0010` par le propriétaire, rejoué en HTTP réel avec deux comptes
anonymes frais (clé publique uniquement, aucune donnée conservée) : **25 PASS / 26**.

| Chemin vérifié | Résultat réel |
|---|---|
| `0010` appliquée (`GET message_reactions` → 200, pas `PGRST205`) | PASS |
| Création profils A/B, conversation (201, membre auto via trigger), relecture | PASS |
| Renommage (`PATCH conversations` + relecture du nouveau titre) | PASS |
| Message + réponse (`reply_to_id` relu à l'identique) | PASS |
| Réaction 🍅 (201, relecture 1 ligne), doublon **409** (pas de doublon) | PASS |
| Non-membre : ajout **403**, lecture `[]` (RLS membre confirmée) | PASS |
| Retrait réaction (204, relecture `[]`) | PASS |
| Édition publication (`PATCH posts` + relecture, puis suppression réelle) | PASS |
| Suppression conversation + cascade | **FAIL — voir ci-dessous** |
| Nettoyage (profils A/B supprimés, cascade serveur vérifiée) | PASS |

Deux constats honnêtes, reproduits chacun 3 fois minimum :

1. **`POST /conversations` avec `Prefer: return=representation` → 403 (`42501`) sans créer
   la ligne, alors que la même requête avec `return=minimal` → 201.** Les deux clients
   (Android `representation = false`, PWA `prefer: 'return=minimal'`) utilisent déjà la forme
   qui marche : aucun code applicatif n'est touché. Le script de vérification suit désormais
   exactement le chemin des clients (écriture minimale + `GET` séparé). Cause PostgREST exacte
   non établie ; effet 100 % reproductible, documenté dans le script.
2. **`DELETE /conversations` (politique `conversations_delete_creator`, migration `0008`)
   ne correspond à aucune ligne** : 204/200 avec `[]`, la conversation et ses messages restent.
   `created_by` stocké = `auth.uid()` vérifié par relecture : c'est la politique `0008` qui
   manque côté serveur (le propriétaire n'avait appliqué que `0010`). Conséquence : la
   suppression de conversation de l'app croit réussir (204) sans rien effacer — **en attente
   d'application de `0007`/`0008`/`0009`, toujours dues depuis 0.3.1.** Dès `0008` appliquée,
   relancer `./tooling/verify_040_reactions.sh` (attendu : 26/26).

## 3. Itération 0.4.0-visuel — interface alignée sur les maquettes (10 novembre 2026)

Migrations `0007` → `0010` appliquées par le propriétaire : le script de rejeu réel
`tooling/verify_040_reactions.sh` passe **26 PASS / 0 FAIL** (réponses citées, renommage,
réactions + RLS non-membre, édition de publication, suppression de conversation avec cascade).
Le chemin de notifications a été vérifié séparément : message A→B ⇒ notification `message`
créée par le déclencheur serveur, lue par B (200), `PATCH read_at` (204), relue.

### Ce qui a été refait pour ressembler aux maquettes

| Zone | Avant | Maintenant |
|---|---|---|
| Barre de navigation | `NavigationBar` Material standard, 5 onglets | barre expressive maison : 4 onglets, pilule colorée sous l'onglet actif, **bouton central « + »** qui ouvre une feuille d'actions réelles (story, conversation, communauté, proximité), badge rouge de messages non lus |
| Accueil — en-tête | logo + badge réseau + actualiser | logo + **TOMATE** en gras, **recherche locale** dans le fil, **cloche avec compteur réel** de notifications, **avatar** vers le profil, badge réseau conservé |
| Accueil — stories | pastilles rondes, libellé = pseudo | tuile rose arrondie « + », avatars cerclés (gris = vue), libellé = nom affiché, deux lignes maximum |
| Accueil — publication | carte tonale, date `dd/MM HH:mm`, portée en pastille texte, « Modifier » en chip | carte blanche, **« il y a 12 min »**, **icône de portée** (globe/personnes/cadenas), média **plein cadre**, rangée ❤️/💬/Partager (partage système réel), menu ⋯ **Modifier / Supprimer** |
| Messages | barre simple, cartes empilées, date complète | titre + composeur rouge, **filtres Toutes / Privées / Groupes / Non lues**, lignes plates avatar+nom+aperçu+heure+badge rouge, FAB |
| Communautés | liste de cartes | **bandeau dégradé TOMATE**, « Vos communautés », « Découvrir » en carrousel, pastilles d'icônes |
| Profil | cartes empilées (chiffrement, récupération, blocages) | en-tête centré (anneau, crayon), nom + @pseudo + bio, bouton **Modifier le profil** + partage, **statistiques réelles** (publications, communautés, bloqués), menu à chevrons (Paramètres, Notifications, Confidentialité, Code de récupération, Chiffrement), **Se déconnecter** en pilule rouge. Toutes les fonctions précédentes restent accessibles (récupération, blocages, E2EE, identifiant, file d'attente) |
| Détail communauté | barre de titre + carte | **bannière**, logo superposé, adhésion, **onglets Publications / Membres / À propos**, membres lus sur le serveur |
| Notifications | inexistant | **écran réel** lisant `notifications` (0007), marquage lu / tout lu, navigation vers la conversation ou la publication |
| PWA | pas de partage | action **Partager** (Web Share, repli presse-papiers) ; temps relatifs et médias arrondis déjà en place |

### Ce qui est vérifié, et comment

| Vérification | Résultat |
|---|---|
| `:core` `:data` `:net` `:app` tests JVM | **BUILD SUCCESSFUL**, 0 échec |
| `UiScreenshotTest` (4 rendus Robolectric 360 dp) | navigation, stories, carte de publication, composeur : **PNG produits puis regardés** (`app/build/ui-screenshots/`) |
| `:app:assembleDebug --rerun-tasks` | **BUILD SUCCESSFUL**, `artifacts/tomate-0.4.0-debug.apk` |
| `node --check web/js/app.js web/js/queue.js` | OK ; **aucune action `data-act` sans gestionnaire** (audit automatique) |
| Rejeu HTTP réel (`verify_040_reactions.sh`) | **26/26** |
| Notifications A→B en HTTP réel | notification créée par le serveur, lue, marquée lue |

### Limites (inchangées ou nouvelles, déclarées telles quelles)

- **Toujours aucun appareil ni émulateur** : les écrans sont compilés et quatre zones sont
  rendues en pixels et regardées, mais l'app complète n'a pas été parcourue à la main.
- La **PWA garde 5 onglets** (dont « Réseau ») sans bouton central : la refonte de sa barre
  n'a pas été faite, seules les actions de partage et les temps relatifs sont alignés.
- **Pas d'avatars photo, pas de badge « vérifié », pas de compteurs d'abonnés** : aucune de ces
  données n'existe côté client (aucune table `follows` interrogée). Les afficher serait inventer.
- Le bouton central ouvre les actions existantes ; il ne crée pas de « publication rapide »
  modale : la rédaction reste dans l'accueil.
- Toujours non faits : E2EE réel, relais A→B→C sur trois téléphones, modération administrateur,
  assistant, appels de groupe.
