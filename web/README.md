# TOMATE Web (PWA)

Client web installable de TOMATE : même backend Supabase, même compte, fonctionne hors ligne.

- Fonctionnalités et limites : [`docs/07_PWA.md`](../docs/07_PWA.md)
- Aperçu publié : https://site-1b6f0f4eb331483ca0b74abd4600dd43.freebuff.page

## Lancer en local

```bash
cd web
python3 -m http.server 3000 --bind 0.0.0.0
```

Ouvrir http://localhost:3000. Le service worker exige `localhost` ou HTTPS.

## Structure

| Fichier | Rôle |
|---|---|
| `index.html` | coquille HTML |
| `manifest.webmanifest` | installation PWA |
| `sw.js` | précache de la coquille + repli hors ligne |
| `css/app.css` | design system clair/sombre |
| `js/config.js` | URL et clé **publishable** Supabase |
| `js/db.js` | IndexedDB (`kv`, `cache`, `outbox`) |
| `js/api.js` | auth anonyme, REST, liaison du compte local |
| `js/queue.js` | file d'attente idempotente |
| `js/weather.js` | Open-Meteo + cache daté |
| `js/app.js` | routes et vues |

Aucun secret n'est présent : la clé publishable est destinée au client, les politiques RLS
protègent les données.
