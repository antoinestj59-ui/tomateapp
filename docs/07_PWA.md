# TOMATE Web — PWA installable

Client web de TOMATE : installable, hors ligne après le premier chargement, branché sur le
**même backend Supabase** et le **même compte** que l'application Android.

| Élément | Valeur |
|---|---|
| Code | `web/` (HTML/CSS/JS sans build, modules ES) |
| Déploiement | `sites.web.json` + `tooling/web-worker.js` (assets statiques) |
| Aperçu publié | https://site-1b6f0f4eb331483ca0b74abd4600dd43.freebuff.page |
| Version | `0.1.3-web` |

## 1. Ce que la PWA fait réellement

- **Compte** : auth anonyme Supabase ; **compte local** `local-<uuid>` créé hors ligne puis relié
  automatiquement au réseau (`linkLocalAccount`), avec réécriture des identifiants locaux dans le
  cache et la file d'attente.
- **Fil** : lecture de la vue `feed`, publication, réaction « tomate », commentaires, suppression
  de ses propres publications.
- **Messagerie** : liste des conversations, création par pseudo, envoi de messages texte avec
  `client_id` (déduplication), états « en attente » / envoyé / **non envoyé** quand le serveur
  refuse définitivement une opération.
- **Communautés** : liste, création, adhésion.
- **Météo** : Open-Meteo réel (actuel, horaire, 5 jours, UV, lever/coucher), recherche de ville,
  géolocalisation sur action explicite, **cache daté** consultable hors ligne.
- **Hors ligne** : IndexedDB (profil, caches, file d'attente), service worker qui précache la
  coquille, rejeu automatique de la file au retour du réseau.
- **Réseau** : page d'état et de **capacités réellement détectées** (contexte sécurisé, Web
  Bluetooth, partage, notifications, géolocalisation, stockage, synchronisation d'arrière-plan),
  test Bluetooth réel via le sélecteur du navigateur, test de notification locale.
- **Paramètres** : thème système/clair/sombre, installation, estimation de stockage, vidage du
  cache de contenu, déconnexion qui efface les données locales.

## 2. Ce que la PWA ne fait pas — et ne prétend pas faire

| Fonction Android | Statut web |
|---|---|
| Mesh BLE en arrière-plan, Wi-Fi Direct | **impossible** dans un navigateur : expliqué dans l'écran Réseau |
| Relais multi-sauts entre téléphones | **absent** de la PWA (Android uniquement) |
| Chiffrement de bout en bout | **absent**, aucun libellé ne le prétend |
| Récupération de compte par code | **absente** |
| Notifications push | **absentes** (notifications locales seulement) |
| Appels audio/vidéo | **absents** |
| Stories | **testées en ligne** : création **201**, visible par un autre compte, vue enregistrée, story écrite hors ligne envoyée à la reconnexion, suppression par l'auteur. Bandeau, visionneuse 24 h, composeur, **photo/vidéo** (nécessite `0005_media_reseau.sql`). |
| Ajout par QR | QR personnel généré, scan caméra si `BarcodeDetector`, lien collé, conversation créée même hors ligne. |
| Publications photo/vidéo | code livré, upload vers le bucket `posts` avant l'écriture (migration `0006_social.sql` requise) ; refus honnête et média conservé tant que le bucket manque. |
| Publication dans un groupe | composeur dans la page du groupe, `visibility=Community`, `community_id` vérifié côté serveur ; message vu par le propriétaire. |
| Découverte des groupes | liste des groupes publics, lien d'invitation copiable, liste des membres (rôles) une fois `0006` appliquée. |
| Recherche globale | personnes, groupes et publications via PostgREST ; hors ligne, recherche limitée au cache et annoncée. |
| Brouillons | fil et groupes, conservés dans `localStorage` après rechargement. |
| Flammes 🔥 | Jours consécutifs où les deux ont écrit, calculés depuis les messages, affichés hors ligne. |
| Événements, cartes hors ligne | **non portés** (itérations futures) |

Les permissions ne sont demandées qu'au moment de l'action, avec l'explication affichée à côté.
Refuser une permission n'empêche jamais l'usage du reste.

## 3. Architecture

```
web/
  index.html              coquille
  manifest.webmanifest    installation
  sw.js                   précache + repli hors ligne
  css/app.css             design system (clair/sombre)
  icons/                  192, 512, maskable
  js/config.js            URL + clé publishable (aucun secret)
  js/db.js                IndexedDB : kv, cache, outbox
  js/api.js               auth anonyme, refresh, REST, liaison du compte local
  js/queue.js             file d'attente idempotente
  js/weather.js           Open-Meteo + cache
  js/app.js               routes, vues, actions
```

Choix assumés : pas de framework ni d'étape de build (moins de surface, déploiement direct des
fichiers), contenu utilisateur inséré via `textContent` (pas d'injection HTML), écritures
idempotentes sur des identifiants générés côté client. `merge-duplicates` n'est utilisé que sur
les tables qui ont une politique UPDATE ; ailleurs c'est `ignore-duplicates`, et `conversations`
reçoit un insert simple (`return=minimal`), sinon PostgREST refuse en 403.

Service worker : précache avec `cache: reload` à l'installation, et **réseau d'abord** pour les
scripts et styles (repli cache hors ligne) — un déploiement ne peut plus servir un mélange de
versions JS.

## 4. Tests réellement effectués (8 novembre 2026)

Environnement : navigateur cloud (Chromium), backend Supabase du projet, serveur local et
déploiement d'aperçu.

| Test | Résultat |
|---|---|
| Auth anonyme `POST /auth/v1/signup` | **HTTP 200**, JWT reçu |
| Création de profil | profil UUID créé puis visible dans le fil |
| Publication en ligne | `POST /rest/v1/posts` réussi, `pending=false`, file vide |
| Réaction | persistée, compteur à 1, file vide |
| Commentaire | **vérifié côté serveur** par requête REST authentifiée |
| Conversation + membres | `POST conversations` **201**, deux `conversation_members` **201** |
| Message PWA → second compte | **201**, relu avec le jeton de `tomate_peer` |
| Réponse du second compte → PWA | reçue et affichée après rechargement |
| Message hors ligne | mis en file, **reçu par le second compte** après reconnexion, file à 0 |
| Opération refusée (403) | badge **Non envoyé**, jamais « en attente » à l'infini |
| Publication hors ligne simulée | mise en file, badge « En attente d'envoi », aucune erreur affichée |
| Reconnexion | file rejouée au rechargement, **les deux publications présentes sur le serveur**, file à 0 |
| Précache du service worker | 12 entrées (`/`, JS, CSS, manifeste, icônes) |
| Déploiement d'aperçu | manifeste 200 `application/manifest+json`, SW actif sur l'origine déployée |
| Météo | données réelles Lyon (11 °C, horaire + 5 jours), cache IndexedDB |
| Mise en page mobile | barre inférieure à 5 entrées, libellés lisibles |

### Non testé (à ne pas présenter comme validé)

- **Rechargement totalement hors ligne** : le proxy d'aperçu suspend la requête au lieu de la
  faire échouer, le test n'a donc pas pu être conclu ; seule la présence du précache est vérifiée.
- **Installation** (`beforeinstallprompt`) : non déclenchée dans le navigateur de test.
- **Web Bluetooth** : aucun appareil à portée ; seul le refus/annulation a été observé.
- **RLS** : **vérifiée** côté serveur (10 assertions PASS, deux comptes) ; aucun test d'accès
  croisé ne reste en attente.

## 5. Lancer / déployer

```bash
# local
cd web && python3 -m http.server 3000 --bind 0.0.0.0

# déploiement (aperçu)
sites_deploy manifestPath=sites.web.json environment=preview
```

Aucune variable d'environnement n'est nécessaire : la clé publishable est publique par
construction, et les RLS protègent les données.
