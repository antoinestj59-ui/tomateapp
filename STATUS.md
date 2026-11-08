# STATUS — TOMATE 0.1.3

Dernière vérification : **8 novembre 2026** (VM Linux, JDK 21, Gradle 9.9.0, AGP 9.5.1) et
navigateur cloud Chromium pour la PWA.

Ce fichier décrit **uniquement ce qui a été exécuté**. Le non-testé est déclaré comme tel.

## 1. Android — build et tests

| Commande | Résultat |
|---|---|
| `:app:assembleDebug` | **BUILD SUCCESSFUL**, exit 0 |
| APK | `artifacts/tomate-0.1.3-debug.apk` — 23 Mo, `versionCode 4` |
| `:core:testDebugUnitTest :data:testDebugUnitTest :net:testDebugUnitTest` | **BUILD SUCCESSFUL**, 0 échec (data 15, net 15) |
| `:data:assembleDebugAndroidTest` | compilé, non exécuté (appareil requis) |

## 2. PWA — build et tests réels

| Élément | Valeur |
|---|---|
| Code | `web/` (sans build), `sites.web.json` |
| Aperçu publié | https://site-1b6f0f4eb331483ca0b74abd4600dd43.freebuff.page |
| Version | `0.1.2-web` |
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

Chiffrement de bout en bout, récupération de compte, suppression de compte serveur, stories,
événements, cartes hors ligne, appels, notifications push, mesh navigateur.

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
