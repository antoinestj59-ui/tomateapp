// File d'attente hors ligne de la PWA : chaque opération est rejouable et idempotente.
import { addItem, cacheGet, cacheSet, countItems, getAll, getAllKeys, removeItem } from './db.js';
import { HttpError, ensureRemoteSession, rest } from './api.js';

export const enqueue = (kind, payload) => addItem('outbox', { kind, payload, createdAt: Date.now() });

export const pendingCount = () => countItems('outbox');

export async function pendingOperations() {
  return getAll('outbox');
}

const ENDPOINTS = {
  post_insert: (p) => ['posts', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'id' }],
  post_delete: (p) => ['posts', { method: 'DELETE', query: `id=eq.${p.id}` }],
  comment_insert: (p) => ['post_comments', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'id' }],
  reaction_add: (p) => ['post_reactions', { method: 'POST', body: p, prefer: 'return=minimal,resolution=ignore-duplicates', onConflict: 'post_id,user_id,kind' }],
  reaction_remove: (p) => ['post_reactions', { method: 'DELETE', query: `post_id=eq.${p.post_id}&user_id=eq.${p.user_id}&kind=eq.${p.kind ?? 'tomate'}` }],
  profile_upsert: (p) => ['profiles', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'id' }],
  community_create: (p) => ['communities', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'id' }],
  community_join: (p) => ['community_members', { method: 'POST', body: p, prefer: 'return=minimal,resolution=ignore-duplicates', onConflict: 'community_id,user_id' }],
  // return=minimal: PostgREST would otherwise re-read the new row and apply the SELECT policy
  // before the creator has been added as a member (403).
  conversation_create: (p) => ['conversations', { method: 'POST', body: p, prefer: 'return=minimal' }],
  conversation_member_add: (p) => ['conversation_members', { method: 'POST', body: p, prefer: 'return=minimal,resolution=ignore-duplicates', onConflict: 'conversation_id,user_id' }],
  message_send: (p) => ['messages', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'id' }],
  // Plain insert: stories has no UPDATE policy, a merge-duplicates would be refused (403).
  story_create: (p) => ['stories', { method: 'POST', body: p, prefer: 'return=minimal' }],
  // story_views has no UPDATE policy: ignoring the duplicate is the only correct replay.
  story_view: (p) => ['story_views', { method: 'POST', body: p, prefer: 'return=minimal,resolution=ignore-duplicates', onConflict: 'story_id,viewer_id' }],
  story_delete: (p) => ['stories', { method: 'DELETE', query: `id=eq.${p.id}` }],
  report_create: (p) => ['reports', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'id' }],
};

async function markFailed(kind, payload) {
  // A permanently refused operation must not look pending forever.
  if (kind === 'post_insert') {
    const feed = (await cacheGet('feed')) ?? [];
    await cacheSet('feed', feed.map((p) => (p.id === payload.id ? { ...p, pending: false, failed: true } : p)));
  }
  if (kind === 'comment_insert') {
    const key = `comments:${payload.post_id}`;
    const comments = (await cacheGet(key)) ?? [];
    await cacheSet(key, comments.map((c) => (c.id === payload.id ? { ...c, pending: false, failed: true } : c)));
  }
  if (kind === 'message_send') {
    const key = `messages:${payload.conversation_id}`;
    const messages = (await cacheGet(key)) ?? [];
    await cacheSet(key, messages.map((m) => (m.id === payload.id ? { ...m, pending: false, failed: true } : m)));
  }
  if (kind === 'story_create') {
    const stories = (await cacheGet('stories')) ?? [];
    await cacheSet('stories', stories.map((s) => (s.id === payload.id ? { ...s, pending: false, failed: true } : s)));
  }
}

async function markSynced(kind, payload) {
  if (kind === 'post_insert') {
    const feed = (await cacheGet('feed')) ?? [];
    await cacheSet('feed', feed.map((p) => (p.id === payload.id ? { ...p, pending: false } : p)));
  }
  if (kind === 'comment_insert') {
    const key = `comments:${payload.post_id}`;
    const comments = (await cacheGet(key)) ?? [];
    await cacheSet(key, comments.map((c) => (c.id === payload.id ? { ...c, pending: false } : c)));
  }
  if (kind === 'message_send') {
    const key = `messages:${payload.conversation_id}`;
    const messages = (await cacheGet(key)) ?? [];
    await cacheSet(key, messages.map((m) => (m.id === payload.id ? { ...m, pending: false } : m)));
  }
  if (kind === 'story_create') {
    const stories = (await cacheGet('stories')) ?? [];
    await cacheSet('stories', stories.map((s) => (s.id === payload.id ? { ...s, pending: false } : s)));
  }
}

/**
 * Cached rows can be left "pending" by an app version that dropped an operation without a
 * badge. Anything pending that has no matching outbox row is reported as not sent.
 */
async function reconcilePending() {
  const ops = await getAll('outbox');
  const queued = new Set(ops.map((op) => String(op.payload?.id)));
  const keys = (await getAllKeys('cache')).filter(
    (key) =>
      key === 'feed' ||
      key === 'stories' ||
      String(key).startsWith('messages:') ||
      String(key).startsWith('comments:'),
  );
  for (const key of keys) {
    const list = await cacheGet(key);
    if (!Array.isArray(list)) continue;
    let changed = false;
    const next = list.map((item) => {
      if (!item?.pending || queued.has(String(item.id))) return item;
      changed = true;
      return { ...item, pending: false, failed: true };
    });
    if (changed) await cacheSet(key, next);
  }
}

/**
 * Replays queued operations. Stops on a network failure (retry later), drops duplicates
 * (409) and permanent validation errors instead of retrying them forever.
 */
export async function flushQueue() {
  const session = await ensureRemoteSession();
  if (!session) return { flushed: 0, remaining: await pendingCount(), offline: true };
  const ops = await getAll('outbox');
  let flushed = 0;
  let lastError = null;
  for (const op of ops) {
    const endpoint = ENDPOINTS[op.kind];
    if (!endpoint) {
      await removeItem('outbox', op.id);
      continue;
    }
    const [table, options] = endpoint(op.payload);
    try {
      await rest(table, { ...options, retryAuth: false });
      await removeItem('outbox', op.id);
      await markSynced(op.kind, op.payload);
      flushed += 1;
    } catch (error) {
      if (error instanceof HttpError && error.status === 409) {
        // Same client-generated id already on the server: nothing to do.
        await removeItem('outbox', op.id);
        await markSynced(op.kind, op.payload);
        flushed += 1;
        continue;
      }
      if (error instanceof HttpError && error.network) {
        lastError = 'Réseau indisponible : les opérations restent en attente.';
        break;
      }
      if (error instanceof HttpError && (error.status === 400 || error.status === 422 || error.status === 403)) {
        lastError = `Opération refusée par le serveur (${error.status}) : ${String(error.body).slice(0, 160)}`;
        await markFailed(op.kind, op.payload);
        await removeItem('outbox', op.id);
        continue;
      }
      lastError = error.message;
      break;
    }
  }
  await reconcilePending();
  return { flushed, remaining: await pendingCount(), lastError };
}
