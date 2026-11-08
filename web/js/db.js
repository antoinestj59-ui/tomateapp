// IndexedDB : stockage local de la PWA (profil, caches, file d'attente hors ligne).

const DB_NAME = 'tomate-web';
const DB_VERSION = 1;
let dbPromise = null;

export function openDb() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('kv')) db.createObjectStore('kv');
      if (!db.objectStoreNames.contains('cache')) db.createObjectStore('cache');
      if (!db.objectStoreNames.contains('outbox')) {
        db.createObjectStore('outbox', { keyPath: 'id', autoIncrement: true });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return dbPromise;
}

function run(store, mode, action) {
  return openDb().then(
    (db) =>
      new Promise((resolve, reject) => {
        const tx = db.transaction(store, mode);
        const request = action(tx.objectStore(store));
        tx.oncomplete = () => resolve(request ? request.result : undefined);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
      }),
  );
}

export const getItem = (store, key) => run(store, 'readonly', (s) => s.get(key));
export const setItem = (store, key, value) => run(store, 'readwrite', (s) => s.put(value, key));
export const removeItem = (store, key) => run(store, 'readwrite', (s) => s.delete(key));
export const getAll = (store) => run(store, 'readonly', (s) => s.getAll());
export const addItem = (store, value) => run(store, 'readwrite', (s) => s.add(value));
export const countItems = (store) => run(store, 'readonly', (s) => s.count());

export async function clearStore(store) {
  await run(store, 'readwrite', (s) => s.clear());
}

/** Used by "Se déconnecter": nothing about the account may survive on the device. */
export async function clearEverything() {
  await Promise.all(['kv', 'cache', 'outbox'].map((store) => clearStore(store)));
}

export const cacheGet = (key) => getItem('cache', key);
export const cacheSet = (key, value) => setItem('cache', key, value);
export const cacheDelete = (key) => removeItem('cache', key);
