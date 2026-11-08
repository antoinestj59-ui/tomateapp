// File d'attente hors ligne de la PWA : chaque opération est rejouable et idempotente.
import { addItem, cacheGet, cacheSet, countItems, getAll, removeItem } from './db.js';
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
  reaction_add: (p) => ['post_reactions', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'post_id,user_id,kind' }],
  reaction_remove: (p) => ['post_reactions', { method: 'DELETE', query: `post_id=eq.${p.post_id}&user_id=eq.${p.user_id}&kind=eq.${p.kind ?? 'tomate'}` }],
  profile_upsert: (p) => ['profiles', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'id' }],
  community_create: (p) => ['communities', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'id' }],
  community_join: (p) => ['community_members', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'community_id,user_id' }],
  conversation_create: (p) => ['conversations', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'id' }],
  conversation_member_add: (p) => ['conversation_members', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'conversation_id,user_id' }],
  message_send: (p) => ['messages', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'id' }],
  report_create: (p) => ['reports', { method: 'POST', body: p, prefer: 'resolution=merge-duplicates', onConflict: 'id' }],
};

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
        await removeItem('outbox', op.id);
        continue;
      }
      lastError = error.message;
      break;
    }
  }
  return { flushed, remaining: await pendingCount(), lastError };
}
