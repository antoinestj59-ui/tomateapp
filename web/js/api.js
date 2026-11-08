// Accès Supabase pour la PWA : auth anonyme, rafraîchissement, REST, compte local hors ligne.
import { LOCAL_PREFIX, SUPABASE_KEY, SUPABASE_URL } from './config.js';
import { getItem, setItem, removeItem, getAll, cacheGet, cacheSet } from './db.js';

export class HttpError extends Error {
  constructor(status, body, network = false) {
    super(`HTTP ${status}: ${String(body).slice(0, 300)}`);
    this.name = 'HttpError';
    this.status = status;
    this.body = body;
    this.network = network;
  }
}

let sessionCache;
let sessionLoaded = false;

export async function getSession() {
  if (!sessionLoaded) {
    sessionCache = (await getItem('kv', 'session')) ?? null;
    sessionLoaded = true;
  }
  return sessionCache;
}

async function storeSession(session) {
  sessionCache = session;
  sessionLoaded = true;
  if (session) await setItem('kv', 'session', session);
  else await removeItem('kv', 'session');
}

/** Removes the local session (used by sign-out, which also wipes IndexedDB). */
export async function clearSession() {
  await storeSession(null);
}

export function isLocalId(id) {
  return typeof id === 'string' && id.startsWith(LOCAL_PREFIX);
}

function normalize(json) {
  const userId = json?.user?.id;
  if (!userId) throw new HttpError(500, 'réponse auth sans identifiant');
  return {
    accessToken: json.access_token,
    refreshToken: json.refresh_token ?? null,
    expiresAt: Date.now() + (Number(json.expires_in ?? 3600) - 30) * 1000,
    userId,
    local: false,
  };
}

async function authRequest(path, body) {
  let response;
  try {
    response = await fetch(`${SUPABASE_URL}/auth/v1/${path}`, {
      method: 'POST',
      headers: { apikey: SUPABASE_KEY, 'Content-Type': 'application/json' },
      body: JSON.stringify(body ?? {}),
    });
  } catch (e) {
    throw new HttpError(0, e.message, true);
  }
  const text = await response.text();
  if (!response.ok) throw new HttpError(response.status, text);
  return normalize(JSON.parse(text));
}

export function signUpAnonymous() {
  return authRequest('signup', {});
}

/**
 * A local identity is a real account for the user: it is persisted, usable offline,
 * and migrated to Supabase as soon as the network allows it.
 */
export async function createLocalSession() {
  const id = LOCAL_PREFIX + crypto.randomUUID();
  const session = { accessToken: null, refreshToken: null, expiresAt: Number.MAX_SAFE_INTEGER, userId: id, local: true };
  await storeSession(session);
  return session;
}

async function refresh(session) {
  if (!session?.refreshToken) return null;
  try {
    const refreshed = await authRequest('token?grant_type=refresh_token', { refresh_token: session.refreshToken });
    await storeSession(refreshed);
    return refreshed;
  } catch {
    return null;
  }
}

/** Returns a usable remote session, or null for a local account / no network. */
export async function ensureRemoteSession() {
  let session = await getSession();
  if (!session) return null;
  if (session.local) return null;
  if (session.expiresAt > Date.now()) return session;
  session = await refresh(session);
  return session;
}

/** Always resolves: remote account when possible, otherwise a persisted local identity. */
export async function ensureActor() {
  const existing = await getSession();
  if (existing?.local) return existing;
  if (navigator.onLine) {
    const remote = await ensureRemoteSession();
    if (remote) return remote;
    if (!existing) {
      try {
        const session = await signUpAnonymous();
        await storeSession(session);
        return session;
      } catch {
        // Server unreachable or anonymous sign-in refused: fall through to a local identity.
      }
    }
  }
  return existing ?? createLocalSession();
}

/** Connecte le compte local au réseau et réécrit les identifiants locaux en une passe. */
export async function linkLocalAccount(profile) {
  const session = await getSession();
  if (!session?.local || !navigator.onLine || !profile) return { linked: false };
  let remote;
  try {
    remote = await signUpAnonymous();
  } catch {
    return { linked: false };
  }
  const oldId = session.userId;
  const newId = remote.userId;
  await storeSession(remote);

  const feed = (await cacheGet('feed')) ?? [];
  const rewrittenFeed = feed.map((post) =>
    post.author_id === oldId ? { ...post, author_id: newId } : post,
  );
  await cacheSet('feed', rewrittenFeed);

  const conversations = (await cacheGet('conversations')) ?? [];
  await cacheSet(
    'conversations',
    conversations.map((c) => (c.created_by === oldId ? { ...c, created_by: newId } : c)),
  );

  const ops = await getAll('outbox');
  for (const op of ops) {
    const payload = { ...op.payload };
    if (payload.author_id === oldId) payload.author_id = newId;
    if (payload.user_id === oldId) payload.user_id = newId;
    if (payload.sender_id === oldId) payload.sender_id = newId;
    if (payload.created_by === oldId) payload.created_by = newId;
    if (payload.owner_id === oldId) payload.owner_id = newId;
    if (op.kind === 'profile_upsert') payload.id = newId;
    await setItem('outbox', op.id, { ...op, payload });
  }

  const nextProfile = { ...profile, id: newId };
  await setItem('kv', 'profile', nextProfile);
  try {
    await rest('profiles', {
      method: 'POST',
      body: { id: newId, handle: profile.handle, display_name: profile.display_name, bio: profile.bio ?? '', visibility: profile.visibility ?? 'Public' },
      prefer: 'resolution=merge-duplicates',
      onConflict: 'id',
    });
  } catch {
    // The profile upsert stays queued; the account itself is already linked.
    const { enqueue } = await import('./queue.js');
    await enqueue('profile_upsert', { id: newId, handle: profile.handle, display_name: profile.display_name, bio: profile.bio ?? '', visibility: profile.visibility ?? 'Public' });
  }
  return { linked: true, newId, profile: nextProfile };
}

async function send(method, table, { query = '', body, prefer, onConflict } = {}) {
  const session = await getSession();
  if (!session || session.local) throw new HttpError(401, 'compte local', true);
  const url = new URL(`${SUPABASE_URL}/rest/v1/${table}`);
  if (query) url.search = query;
  if (onConflict) url.searchParams.set('on_conflict', onConflict);
  const headers = {
    apikey: SUPABASE_KEY,
    Authorization: `Bearer ${session.accessToken}`,
    'Content-Type': 'application/json',
  };
  if (prefer) headers.Prefer = prefer;
  let response;
  try {
    response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (e) {
    throw new HttpError(0, e.message, true);
  }
  const text = await response.text();
  if (response.status === 401) {
    const refreshed = await refresh(session);
    if (refreshed) throw new HttpError(401, 'jeton rafraîchi, réessayez');
  }
  if (!response.ok) throw new HttpError(response.status, text);
  return text ? JSON.parse(text) : null;
}

/**
 * REST helper. `retryAuth: false` is used by the queue, which calls ensureRemoteSession first.
 */
export async function rest(table, options = {}) {
  if (options.retryAuth === false) return send(options.method ?? 'GET', table, options);
  const session = await ensureRemoteSession();
  if (!session) throw new HttpError(401, 'session indisponible', true);
  try {
    return await send(options.method ?? 'GET', table, options);
  } catch (error) {
    if (error instanceof HttpError && error.status === 401) {
      const refreshed = await ensureRemoteSession();
      if (refreshed) return send(options.method ?? 'GET', table, options);
    }
    throw error;
  }
}

export async function fetchMyProfile() {
  const session = await ensureRemoteSession();
  if (!session) return null;
  const rows = await rest('profiles', { query: `select=*&id=eq.${session.userId}` });
  return rows?.[0] ?? null;
}

export async function handleAvailable(handle) {
  const rows = await rest('profiles', { query: `select=id&handle=eq.${encodeURIComponent(handle)}` });
  return !rows || rows.length === 0;
}
