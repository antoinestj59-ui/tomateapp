// Service worker TOMATE : la coquille de l'app reste utilisable hors ligne.
const CACHE = 'tomate-shell-v34';
const SHELL = [
  '/',
  '/index.html',
  '/css/app.css',
  '/js/app.js',
  '/js/api.js',
  '/js/db.js',
  '/js/queue.js',
  '/js/weather.js',
  '/js/extras.js',
  '/js/config.js',
  '/vendor/qrcode.js',
  '/manifest.webmanifest',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      // `cache: reload` bypasses the HTTP cache: a version bump must never re-precache stale JS.
      .then((cache) => cache.addAll(SHELL.map((path) => new Request(path, { cache: 'reload' }))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  // Supabase, Open-Meteo and anything else cross-origin stay online-only: their data is cached
  // deliberately in IndexedDB (with timestamps), never smuggled into an opaque HTTP cache.
  if (url.origin !== self.location.origin) return;

  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then(async (response) => {
          // A proxy error (502/503) is not an app: keep the cached shell instead of showing it.
          if (!response.ok && response.status >= 500) {
            return (await caches.match('/index.html')) ?? response;
          }
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put('/index.html', copy));
          return response;
        })
        .catch(async () => (await caches.match('/index.html')) ?? (await caches.match('/'))),
    );
    return;
  }

  // Code and stylesheets are network-first: a deploy must never serve mixed JS versions.
  // Offline still works through the cached copy.
  if (['script', 'style', 'worker'].includes(request.destination)) {
    event.respondWith(
      fetch(request, { cache: 'no-cache' })
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(async () => (await caches.match(request)) ?? Response.error()),
    );
    return;
  }

  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(() => cached);
      return cached ?? network;
    }),
  );
});
