# 09 — Matrice de suivi des fonctionnalités

Audit du dépôt au **9 novembre 2026**, après l'itération 0.1.6.

Légende des états — aucune ligne ne mélange deux états :

| Code | Signification |
|---|---|
| **V-WEB** | exécuté et vérifié dans le navigateur, avec un vrai compte Supabase |
| **V-API** | vérifié par requêtes HTTP réelles contre le projet Supabase (RLS comprise) |
| **C-AND** | compilé dans l'APK, jamais exécuté sur appareil (aucun appareil ici) |
| **PART** | une partie seulement existe ; le manque est décrit |
| **ABS** | absent, et annoncé comme absent dans l'interface |

## 1. Comptes et identité

| Fonction | État | Ce qui manque / dépendance | Priorité | Test de validation |
|---|---|---|---|---|
| Compte anonyme (sans e-mail) | V-WEB, V-API | — | — | `POST /auth/v1/signup` → 200, profil créé |
| Compte hors ligne puis relié | V-WEB | — | — | créer hors ligne, reconnecter, écrire : 201 |
| Pseudo unique + nom affiché | V-WEB | — | — | pseudo déjà pris → message explicite |
| Photo de profil | ABS | bucket `avatars`, colonne `avatar_path` déjà présente | P2 | téléverser une photo, la voir depuis un autre compte |
| Récupération de compte | ABS | fonction serveur + moyen de preuve (clé de récupération) | **P1** | restaurer un compte sur un second appareil |
| Révocation d'appareil | ABS | table `devices` + jetons révocables | P1 | révoquer un appareil, ses écritures passent en 401 |
| QR d'ajout d'ami | V-WEB | scan caméra Android absent | P2 | deux comptes, QR scanné/collé → conversation |

## 2. Fil social

| Fonction | État | Manque / dépendance | Priorité | Test |
|---|---|---|---|---|
| Publication texte | V-WEB, C-AND | — | — | publier → ligne `posts`, visible par un autre compte |
| Publication photo/vidéo | PART : code PWA + Android, **envoi bloqué** tant que `0006_social.sql` n'est pas appliquée | migration `0006_social.sql` | **P0** | photo → `201` storage puis ligne `posts` avec `media_path` |
| Commentaires | V-WEB | — | — | commenter, compter, relire |
| Réactions | V-WEB | — | — | aimer/retirer, compteur cohérent |
| Suppression par l'auteur | V-WEB | — | — | supprimer → invisible pour l'autre compte |
| Édition d'une publication | ABS | policy UPDATE existe déjà | P2 | modifier, horodatage `updated_at` |
| Pièces jointes multiples | ABS | table `post_media` | P3 | 4 photos dans une publication |
| Brouillon conservé | V-WEB (localStorage) | — | — | écrire, recharger, texte restauré |
| Fil personnalisé (abonnements) | PART : table `follows`, pas d'algorithme | page « Abonnements » | P2 | suivre un compte, filtrer le fil |

## 3. Stories

| Fonction | État | Manque | Priorité | Test |
|---|---|---|---|---|
| Story texte 24 h | V-WEB + V-API (expiration testée) | — | — | story expirée → absente de `stories_feed` |
| Story photo/vidéo | PART : code livré, envoi bloqué sans `0005` | migration `0005_media_reseau.sql` | **P0** | photo → storage 200 puis ligne |
| Vues et liste des spectateurs | V-WEB + V-API | — | — | B voit, A lit les vues |
| Réponse à une story | ABS | table `story_replies` | P2 | répondre → message privé |

## 4. Communautés (groupes)

| Fonction | État | Manque | Priorité | Test |
|---|---|---|---|---|
| Créer un groupe | V-WEB, V-API | — | — | `201`, créateur Owner via trigger |
| Découvrir un groupe public depuis un autre compte | **V-API** (compte B voit le groupe de A) | — | — | B liste `communities` → groupe visible |
| Rejoindre un groupe public | **V-API** + V-WEB | — | — | `201` sur `community_members` |
| Publier dans un groupe | **V-WEB + V-API** (0.1.6) | — | — | message avec `community_id` lisible par le propriétaire |
| Liste des membres d'un groupe public | code livré (0006) | 0006 non appliquée : la liste reste vide | **P0** | A et B voient les 2 membres |
| Adhésion à un groupe privé = demande en attente | code livré (0006) | 0006 ; UI d'approbation par un modérateur | **P0** | B rejoint privé → statut `Pending`, pas d'accès |
| Invitation par lien | V-WEB (copie du lien) | le lien n'ouvre pas un groupe privé non chargé | P1 | invité ouvre le lien, demande envoyée |
| Rôles et modération (bannir, promouvoir) | ABS | policies UPDATE existent | P1 | modérateur bannit, l'accès tombe |
| Groupes imbriqués / communautés | ABS | table de rattachement | P3 | — |

Le bug « un groupe créé n'est pas découvrable » venait **du client**, pas du serveur :
le serveur était correct (vérifié par API), mais la PWA renvoyait une adhésion `Owner`
refusée par la RLS (403) et n'offrait aucun composeur dans le groupe. Les deux sont corrigés.

## 5. Messagerie

| Fonction | État | Manque | Priorité | Test |
|---|---|---|---|---|
| Conversation 1-1 (en ligne) | V-WEB + V-API | — | — | 4 messages croisés lus des deux côtés |
| Conversation 1-1 hors ligne | V-WEB (création locale + file) | — | — | message rédigé hors ligne, parti à la reconnexion |
| Groupes de discussion | PART : colonne `is_group`, pas d'UI | écran de création de groupe | P1 | 3 comptes dans un fil |
| Médias dans les messages | ABS | bucket `messages`, upload | P1 | envoyer une photo, la voir chez l'autre |
| Messages vocaux | ABS | enregistreur + upload | P2 | — |
| Réactions, réponses, édition | ABS | colonnes `reply_to_id` (déjà) | P2 | répondre à un message |
| Accusés envoi/reçu/lu | PART : envoi et attente réels ; reçu/lu non | table `message_receipts` | P2 | lire chez B → statut « lu » chez A |
| Indicateur de saisie | ABS | canal temps réel | P3 | — |
| Appels audio/vidéo | ABS | WebRTC + signalisation ; **ne passera pas par le mesh** | P3 | appel 1-1 entre 2 appareils |
| Flammes 🔥 | V-WEB, C-AND | — | — | 2 jours d'échanges → 🔥 2 |

## 6. Recherche, notifications, services

| Fonction | État | Manque | Priorité | Test |
|---|---|---|---|---|
| Recherche globale (personnes, groupes, publications) | **V-WEB** (0.1.6) | recherche dans les messages | P1 | « Jardin » → 2 groupes ; « qr1 » → personne → conversation |
| Recherche hors ligne | V-WEB (cache seulement, annoncé) | index local complet | P2 | avion : résultats limités au cache |
| Notifications | PART : API locale testée depuis les réglages | push serveur, centre de notifications | P1 | notification reçue app fermée (appareil réel) |
| Météo réelle + cache daté | V-WEB | — | — | couper le réseau, la météo reste avec son âge |
| Événements, sondages, notes, annonces, assistant | ABS | — | P3 | — |

## 7. Réseau, hors ligne, sécurité

| Fonction | État | Manque | Priorité | Test |
|---|---|---|---|---|
| File d'attente hors ligne (PWA) | V-WEB | — | — | 2 publications hors ligne → 2 lignes serveur |
| File d'attente hors ligne (Android) | C-AND | — | — | mode avion, écrire, reconnecter |
| Enveloppe + relais BLE/Wi-Fi borné | C-AND, **jamais sur radio** | courier persistant, test 3 appareils | **P1** | A → B → C sur 3 téléphones |
| Stockage/retransmission différée | ABS | voir `docs/08` étape R1 | **P1** | message déposé pour C, remis au retour |
| Chiffrement de bout en bout | ABS, annoncé | X25519 + AEAD, plan `docs/08` | **P1** | le serveur ne voit que du chiffré |
| RLS vérifiée | V-API (`tooling/rls_smoke_test.sh`, 10 assertions) | policies 0006 à rejouer après migration | — | test RLS après chaque migration |
| Blocage / signalement | V-WEB + V-API | file de modération | P2 | bloquer → contenu masqué |
| Export / suppression des données | ABS | fonctions serveur + UI | P1 | exporter, supprimer, vérifier |

## 8. Administration

| Fonction | État | Manque | Priorité |
|---|---|---|---|
| Rôles serveur vérifiés | PART : `Owner`/`Moderator` par groupe | rôle global `admin` | P1 |
| Interface d'administration | ABS | écran + policies `is_admin()` | P2 |
| File de signalements | ABS | table `reports` existante | P1 |

## 9. Ce qu'il faut faire ensuite, dans l'ordre

1. **Exécuter `0005` puis `0006`** (SQL Editor) : sans elles, aucune photo de story ni de
   publication ne peut être envoyée. Dis-moi quand c'est fait, je rejoue les tests médias.
2. **Courier persistant + test 3 appareils** (Android) : c'est la seule façon honnête de
   dire que la chaîne A → B → C fonctionne. Le protocole est prêt, la persistance non.
3. **Récupération de compte** : sans elle, un téléphone perdu = un compte perdu.
4. **E2EE** avec une bibliothèque reconnue, et seulement ensuite le cadenas dans l'UI.
5. Notifications push, médias dans les messages, puis modération.

Rien de cette liste n'est présenté comme fait dans l'application.
