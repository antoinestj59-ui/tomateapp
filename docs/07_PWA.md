# TOMATE Web — PWA installable

Client web de TOMATE : installable, hors ligne après le premier chargement, branché sur le
**même backend Supabase** et le **même compte** que l'application Android.

| Élément | Valeur |
|---|---|
| Code | `web/` (HTML/CSS/JS sans build, modules ES) |
| Déploiement | `sites.web.json` + `tooling/web-worker.js` (assets statiques) |
| Aperçu publié | https://site-1b6f0f4eb331483ca0b74abd4600dd43.freebuff.page |
| Version | `0.1.2-web` |

## 1. Ce que la PWA fait réellement

- **Compte** : auth anonyme Supabase ; **compte local** `local-<uuid>` créé hors ligne puis relié
  automatiquement au réseau (`linkLocalAccount`), avec réécriture des identifiants locaux dans le
  cache et la file d'attente.
- **Fil** : lecture de la vue `feed`, publication, réaction « tomate », commentaires, suppression
  de ses propres publications.
- **Messagerie** : liste des conversations, création par pseudo, envoi de messages texte avec
  `client_id` (déduplication), états « en attente » / envoyé.
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
| Stories, événements, cartes hors ligne | **non portés** (Android / itérations futures) |

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
idempotentes (`resolution=merge-duplicates` sur des identifiants générés côté client).

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
- **Messagerie de bout en bout** : pas de second compte pour recevoir un message.
- **RLS** : les requêtes passent avec le compte de test, mais aucune tentative d'accès croisé
  n'a été validée (script `tooling/rls_smoke_test.sh` toujours à exécuter).

## 5. Lancer / déployer

```bash
# local
cd web && python3 -m http.server 3000 --bind 0.0.0.0

# déploiement (aperçu)
sites_deploy manifestPath=sites.web.json environment=preview
```

Aucune variable d'environnement n'est nécessaire : la clé publishable est publique par
construction, et les RLS protègent les données.
