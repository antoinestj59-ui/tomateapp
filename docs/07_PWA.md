# 07 — TOMATE Web (PWA installable)

Client web de TOMATE : installable, offline-first, branché sur le **même backend Supabase**
et le **même compte** que l'application Android.

| Élément | Valeur |
|---|---|
| Code source | `web/` (HTML/CSS/JS modulaire ES sans build préalable) |
| Déploiement | `sites.web.json` + `tooling/web-worker.js` |
| Version | `0.5.0-web` |
| Aperçu publié | https://site-1b6f0f4eb331483ca0b74abd4600dd43.freebuff.page |

## 1. Fonctionnalités vérifiées dans la PWA

- **Compte et identité** : authentification anonyme Supabase, compte local hors ligne rattaché
  automatiquement au retour du réseau (`linkLocalAccount`), gestion du profil.
- **Fil d'actualité** : lecture du fil, composition, publication photo et vidéo, réactions,
  commentaires, suppression, édition, partage système avec repli presse-papiers.
- **Stories** : création texte/photo/vidéo, expiration 24 h vérifiée, affichage des vues nominatives,
  suppression par l'auteur.
- **Messagerie & E2EE** : conversations 1-1 et de groupe, envoi local-first avec déduplication,
  citations, réactions aux messages, **chiffrement E2EE 1-1 réel avec `web/js/e2ee.js`** compatible
  octet pour octet avec le moteur Android.
- **Appairage** : génération et affichage de QR codes ZXing, validation de liens d'appairage,
  calcul et affichage du numéro de sécurité.
- **Appels WebRTC** : signalisation complète entre comptes, sonnerie, acceptation/refus, coupure micro
  et caméra, chemin média vérifié par flux synthétiques, diagnostic TURN/STUN.
- **Hors ligne** : stockage local IndexedDB, service worker avec mise en cache de la coquille
  applicative (`tomate-shell-v35`), rejeu automatique des actions au retour du réseau.
- **Météo** : interrogation d'Open-Meteo avec cache daté pour consultation hors ligne.

## 2. Spécificités et limites par rapport à Android

| Fonction | Statut PWA |
|---|---|
| Bluetooth en tâche de fond | Non pris en charge par les standards des navigateurs web |
| Wi-Fi Direct natif | Non supporté dans un contexte web |
| Stockage des clés privées | `localStorage` (chiffré via Keystore sur Android) |
| Chiffrement de groupe | Hors périmètre v0.5.0 (commun à Android et PWA) |
