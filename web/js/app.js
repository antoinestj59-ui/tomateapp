// TOMATE — PWA. Un seul point d'entrée : routes par hash, rendu DOM, hors ligne réel.
import {
  HttpError,
  clearSession,
  ensureActor,
  ensureRemoteSession,
  getSession,
  handleAvailable,
  linkLocalAccount,
  rest,
  signUpAnonymous,
} from './api.js';
import {
  cacheGet,
  cacheSet,
  clearEverything,
  clearStore,
  getAll,
  getItem,
  setItem,
} from './db.js';
import { enqueue, flushQueue, pendingCount } from './queue.js';
import {
  cachedWeather,
  describeCode,
  fetchWeather,
  searchCity,
  weatherAge,
} from './weather.js';
import {
  APP_VERSION,
  MAX_COMMENT_LENGTH,
  MAX_MESSAGE_LENGTH,
  MAX_POST_LENGTH,
} from './config.js';

const app = document.getElementById('app');
const toastEl = document.getElementById('toast');

const S = {
  booting: true,
  profile: null,
  session: null,
  online: navigator.onLine,
  queue: 0,
  lastError: null,
  lastSync: null,
  route: 'feed',
  param: null,
  feed: [],
  comments: [],
  conversations: [],
  messages: [],
  communities: [],
  memberships: new Set(),
  weather: null,
  weatherBusy: false,
  syncBusy: false,
  installPrompt: null,
  btDevice: null,
  theme: 'auto',
  storage: null,
};

const ONLINE = () => navigator.onLine;

// --------------------------------------------------------------------- utils

const esc = (value) =>
  String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const initials = (name) =>
  String(name || '?')
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('') || '?';

function fmtDate(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const diff = Date.now() - date.getTime();
  if (diff < 60000) return "à l'instant";
  if (diff < 3600000) return `il y a ${Math.round(diff / 60000)} min`;
  if (diff < 86400000) return `il y a ${Math.round(diff / 3600000)} h`;
  return date.toLocaleDateString('fr-FR', { day: '2-digit', month: 'short' }) + ' ' + date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
}

function humanError(error) {
  if (!error) return '';
  if (error instanceof HttpError) {
    if (error.network) return 'Réseau indisponible : la PWA continue en local et réessaiera.';
    if (error.status === 409) return 'Ce contenu existe déjà côté serveur.';
    if (error.status === 401 || error.status === 403) return "Le serveur refuse l'accès à cette donnée (session ou règle RLS).";
    if (error.status === 404) return 'Le schéma Supabase est absent : appliquez supabase/ALL_IN_ONE.sql.';
    if (error.status >= 500) return 'Le serveur a renvoyé une erreur. Réessayez plus tard.';
    return `Requête refusée (HTTP ${error.status}).`;
  }
  return error.message || 'Erreur inconnue.';
}

let toastTimer;
function toast(message) {
  toastEl.textContent = message;
  toastEl.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toastEl.hidden = true;
  }, 2800);
}

function confirmDialog({ title, text, confirmLabel, danger = false }) {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.innerHTML = `
      <h2>${esc(title)}</h2>
      <p class="muted">${esc(text)}</p>
      <div class="row">
        <button class="btn btn-soft" data-choice="cancel">Annuler</button>
        <button class="btn ${danger ? 'btn-danger' : 'btn-primary'}" data-choice="ok">${esc(confirmLabel)}</button>
      </div>`;
    document.body.appendChild(dialog);
    dialog.showModal();
    dialog.addEventListener('click', (event) => {
      const choice = event.target.closest('[data-choice]')?.dataset.choice;
      if (choice) {
        dialog.close();
        dialog.remove();
        resolve(choice === 'ok');
      }
    });
    dialog.addEventListener('cancel', () => {
      dialog.remove();
      resolve(false);
    });
  });
}

function promptDialog({ title, label, placeholder, value = '', type = 'text' }) {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.innerHTML = `
      <h2>${esc(title)}</h2>
      <div class="field" style="margin-top:12px">
        <label>${esc(label)}</label>
        <input class="input" id="prompt-input" type="${type}" value="${esc(value)}" placeholder="${esc(placeholder ?? '')}" />
      </div>
      <div class="row">
        <button class="btn btn-soft" data-choice="cancel">Annuler</button>
        <button class="btn btn-primary" data-choice="ok">Valider</button>
      </div>`;
    document.body.appendChild(dialog);
    dialog.showModal();
    dialog.querySelector('#prompt-input').focus();
    const done = (ok) => {
      const raw = dialog.querySelector('#prompt-input').value.trim();
      dialog.close();
      dialog.remove();
      resolve(ok ? raw : null);
    };
    dialog.addEventListener('click', (event) => {
      const choice = event.target.closest('[data-choice]')?.dataset.choice;
      if (choice) done(choice === 'ok');
    });
    dialog.addEventListener('cancel', () => {
      dialog.remove();
      resolve(null);
    });
    dialog.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') {
        event.preventDefault();
        done(true);
      }
    });
  });
}

// --------------------------------------------------------------------- icônes

const svg = (paths) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths}</svg>`;
const ICON = {
  home: svg('<path d="M3 10.5 12 3l9 7.5"/><path d="M5.5 9.5V21h13V9.5"/>'),
  messages: svg('<path d="M21 12a8 8 0 0 1-8 8H7l-4 3v-6.5A8 8 0 1 1 21 12Z"/>'),
  network: svg('<circle cx="12" cy="12" r="2"/><path d="M16.2 7.8a6 6 0 0 1 0 8.4M7.8 7.8a6 6 0 0 0 0 8.4M19.1 4.9a10 10 0 0 1 0 14.2M4.9 4.9a10 10 0 0 0 0 14.2"/>'),
  communities: svg('<circle cx="9" cy="8" r="3"/><path d="M3 20c0-3 2.7-5 6-5s6 2 6 5"/><circle cx="17" cy="9" r="2.5"/><path d="M16 15c2.8.2 5 2.1 5 5"/>'),
  profile: svg('<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-7 8-7s8 3 8 7"/>'),
  heart: svg('<path d="M20.8 5.6a5.5 5.5 0 0 0-7.8 0L12 6.6l-1-1a5.5 5.5 0 0 0-7.8 7.8l8.8 8.8 8.8-8.8a5.5 5.5 0 0 0 0-7.8Z"/>'),
  comment: svg('<path d="M21 11.5A7.5 7.5 0 0 1 13.5 19H8l-5 3v-7.5A7.5 7.5 0 0 1 10.5 7h3A7.5 7.5 0 0 1 21 11.5Z"/>'),
  send: svg('<path d="M22 2 11 13"/><path d="M22 2 15 22l-4-9-9-4 20-7Z"/>'),
  trash: svg('<path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M6 6l1 15h10l1-15"/>'),
  refresh: svg('<path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 3v6h-6"/>'),
  settings: svg('<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9 7 7M17 17l2.1 2.1M19.1 4.9 17 7M7 17l-2.1 2.1"/>'),
  back: svg('<path d="M15 18l-6-6 6-6"/>'),
  plus: svg('<path d="M12 5v14M5 12h14"/>'),
  download: svg('<path d="M12 3v12"/><path d="M7 10l5 5 5-5"/><path d="M4 21h16"/>'),
  weather: svg('<circle cx="8" cy="8" r="3"/><path d="M8 2v1.5M2 8h1.5M3.8 3.8l1 1M12.2 3.8l-1 1"/><path d="M9 19h9a4 4 0 0 0 0-8 5.5 5.5 0 0 0-10.4 1.5A3.5 3.5 0 0 0 9 19Z"/>'),
  people: svg('<circle cx="12" cy="8" r="3.2"/><path d="M6 20c0-3 2.7-5 6-5s6 2 6 5"/>'),
};

// ------------------------------------------------------------------ coquille

const NAV = [
  { id: 'feed', label: 'Accueil', icon: ICON.home, route: '#/feed' },
  { id: 'messages', label: 'Messages', icon: ICON.messages, route: '#/messages' },
  { id: 'network', label: 'Réseau', icon: ICON.network, route: '#/network' },
  { id: 'communities', label: 'Communautés', icon: ICON.communities, route: '#/communities' },
  { id: 'profile', label: 'Profil', icon: ICON.profile, route: '#/profile' },
];

function statusChip() {
  if (!S.online) return `<span class="chip offline">Hors ligne</span>`;
  if (S.session?.local) return `<span class="chip local">Compte local</span>`;
  return `<span class="chip online">En ligne</span>`;
}

function bottomNav() {
  return `<nav class="bottom-nav" aria-label="Navigation principale">${NAV.map((item) => `
    <button class="nav-item ${S.route === item.id ? 'active' : ''}" data-act="nav" data-route="${item.route}" aria-current="${S.route === item.id ? 'page' : 'false'}">
      <span class="nav-pill">${item.icon}</span>
      <span class="nav-label">${item.label}</span>
    </button>`).join('')}</nav>`;
}

function topbar({ title, back = null, actions = '' } = {}) {
  if (back) {
    return `<header class="topbar">
      <div class="row grow">
        <button class="icon-btn" data-act="nav" data-route="${back}" aria-label="Retour">${ICON.back}</button>
        <strong style="font-size:18px">${esc(title)}</strong>
      </div>
      ${actions}
    </header>`;
  }
  return `<header class="topbar">
    <div class="brand">TOMATE</div>
    <div class="row" style="gap:8px">
      ${statusChip()}
      ${actions}
    </div>
  </header>`;
}

const shell = (content, options) => `${topbar(options)}${content}${bottomNav()}`;

function notice(text, tone = 'info', dismiss = false) {
  if (!text) return '';
  return `<div class="notice ${tone}"><span>${esc(text)}</span>${dismiss ? '<button class="btn-link" data-act="dismiss-error" style="margin-left:auto">OK</button>' : ''}</div>`;
}

// ------------------------------------------------------------------- onboarding

function welcomeView() {
  return `${topbar({ actions: '' })}
  <div class="card" style="padding:22px">
    <h1>Bienvenue</h1>
    <p class="muted">TOMATE fonctionne <strong>avec ou sans connexion</strong>. Hors ligne, votre compte est créé sur cet appareil et relié automatiquement au réseau dès qu'Internet revient.</p>
    ${notice(ONLINE() ? 'Connexion disponible : votre pseudo sera vérifié en ligne.' : "Aucun réseau : le compte sera local. Tout fonctionnera, l'envoi attendra la connexion.", ONLINE() ? 'info' : 'warning')}
    <form data-form="onboarding" class="col" style="margin-top:14px">
      <div class="field">
        <label for="handle">Pseudo</label>
        <input class="input" id="handle" name="handle" required minlength="3" maxlength="20" pattern="[a-z0-9_]{3,20}" placeholder="antoine" autocapitalize="off" autocomplete="username" />
        <span class="hint">3 à 20 caractères : lettres minuscules, chiffres, tiret bas.</span>
      </div>
      <div class="field">
        <label for="display">Nom affiché</label>
        <input class="input" id="display" name="display" required maxlength="40" placeholder="Antoine" autocomplete="name" />
      </div>
      <div class="field">
        <label for="bio">Bio (facultatif)</label>
        <textarea class="textarea" id="bio" name="bio" maxlength="280" placeholder="Quelques mots…"></textarea>
      </div>
      <div class="field">
        <label for="visibility">Confidentialité du profil</label>
        <select class="input" id="visibility" name="visibility">
          <option value="Public">Public</option>
          <option value="Followers">Abonnés</option>
          <option value="Private">Privé</option>
        </select>
      </div>
      <button class="btn btn-primary btn-block" type="submit">Créer mon compte</button>
      <p class="hint">Aucune adresse e-mail demandée. Le pseudo ne prouve pas l'identité : la récupération sur un autre appareil n'est pas encore disponible.</p>
    </form>
  </div>`;
}

async function createProfile({ handle, display, bio, visibility }) {
  const cleanHandle = handle.trim().toLowerCase();
  if (!/^[a-z0-9_]{3,20}$/.test(cleanHandle)) throw new Error('Pseudo invalide : 3 à 20 caractères (a-z, 0-9, _).');
  if (!display.trim()) throw new Error('Le nom affiché est obligatoire.');

  let session = S.session ?? (await getSession());
  if (!session) {
    session = await ensureActor();
    S.session = session;
  }
  let profile = { id: session.userId, handle: cleanHandle, display_name: display.trim(), bio: bio.trim(), visibility };

  if (ONLINE() && !session.local) {
    try {
      if (!(await handleAvailable(cleanHandle))) throw new Error('Ce pseudo est déjà pris. Choisissez-en un autre.');
    } catch (error) {
      if (error.message?.includes('déjà pris')) throw error;
      // Availability check unreachable: continue, the server remains authoritative on insert.
    }
    try {
      await rest('profiles', {
        method: 'POST',
        body: profile,
        prefer: 'resolution=merge-duplicates',
        onConflict: 'id',
      });
    } catch (error) {
      if (error instanceof HttpError && error.status === 409) {
        throw new Error('Ce pseudo est déjà pris. Choisissez-en un autre.');
      }
      if (!(error instanceof HttpError && error.network)) throw error;
      await enqueue('profile_upsert', profile);
    }
  } else {
    await enqueue('profile_upsert', profile);
  }

  await setItem('kv', 'profile', profile);
  S.profile = profile;
  S.session = session;
  location.hash = '#/feed';
  await navigate();
  toast(session.local || !ONLINE() ? 'Compte local créé. Il sera relié au réseau.' : 'Compte créé.');
}

// ------------------------------------------------------------------------ fil

function feedView() {
  const pending = S.feed.filter((p) => p.pending).length;
  return shell(`
    ${S.lastError ? notice(S.lastError, 'error', true) : ''}
    ${S.session?.local ? notice('Compte local : vos publications sont conservées et seront envoyées dès que le réseau sera disponible.', 'info') : ''}
    ${pending > 0 ? notice(`${pending} publication(s) en attente d'envoi.`, 'warning') : ''}
    <div class="card">
      <form data-form="publish" class="compose">
        <label for="compose" class="muted small" style="font-weight:600">Partager quelque chose…</label>
        <textarea id="compose" name="body" maxlength="${MAX_POST_LENGTH}" placeholder="Quoi de neuf ?" aria-label="Nouvelle publication"></textarea>
        <div class="row-between">
          <span data-counter="publish">0/${MAX_POST_LENGTH}</span>
          <button class="icon-btn" type="submit" style="color:var(--primary)" aria-label="Publier">${ICON.send}</button>
        </div>
      </form>
    </div>
    ${weatherCard()}
    <div class="section-title"><h2>Fil d'actualité</h2>
      <button class="icon-btn" data-act="refresh" aria-label="Actualiser">${ICON.refresh}</button>
    </div>
    <div class="card" style="padding:4px 16px" id="feed-list">
      ${S.feed.length === 0 ? emptyState('🌱', 'Rien à afficher pour le moment.', 'Publiez le premier message ou actualisez quand vous êtes en ligne.') : S.feed.map(postItem).join('')}
    </div>
  `, { actions: `<button class="icon-btn" data-act="refresh" aria-label="Actualiser">${ICON.refresh}</button>` });
}

function weatherCard() {
  const w = S.weather;
  if (!w) {
    return `<div class="card flat" style="margin-top:12px">
      <div class="row-between"><div><strong>Météo</strong><div class="tiny muted">Prévisions réelles, consultables hors ligne une fois chargées.</div></div>
      <button class="btn btn-soft" data-act="nav" data-route="#/weather">Ouvrir</button></div>
    </div>`;
  }
  const [label, emoji] = describeCode(w.current?.weather_code);
  return `<button class="weather-hero" data-act="nav" data-route="#/weather" style="width:100%;text-align:left;margin-top:12px;cursor:pointer">
    <div>
      <div class="tiny muted">${esc(w.place?.label ?? 'Météo')} · ${weatherAge(w.fetchedAt)}</div>
      <div class="row" style="gap:10px;align-items:baseline">
        <span class="weather-temp">${Math.round(w.current?.temperature_2m ?? 0)}°</span>
        <span class="muted">${esc(label)}</span>
      </div>
    </div>
    <span class="weather-emoji">${emoji}</span>
  </button>`;
}

function postItem(post) {
  const mine = post.author_id === S.profile?.id;
  return `<article class="post">
    <span class="avatar">${initials(post.author_display_name || post.author_handle)}</span>
    <div class="grow">
      <div class="row wrap" style="gap:8px">
        <strong>${esc(post.author_display_name || post.author_handle || 'Anonyme')}</strong>
        <span class="post-meta">@${esc(post.author_handle ?? '')} · ${fmtDate(post.created_at)}</span>
        ${post.pending ? '<span class="pending-label">En attente d\'envoi</span>' : ''}
      </div>
      <div class="post-body" data-body-post="${esc(post.id)}"></div>
      <div class="post-actions">
        <button class="action ${post.reacted_by_me ? 'liked' : ''}" data-act="like" data-id="${esc(post.id)}" aria-pressed="${!!post.reacted_by_me}">
          ${ICON.heart}<span>${post.reaction_count ?? 0}</span>
        </button>
        <button class="action" data-act="open-post" data-id="${esc(post.id)}">
          ${ICON.comment}<span>${post.comment_count ?? 0}</span>
        </button>
        ${mine ? `<button class="action" data-act="delete-post" data-id="${esc(post.id)}" aria-label="Supprimer">${ICON.trash}</button>` : ''}
      </div>
    </div>
  </article>`;
}

function emptyState(emoji, title, hint) {
  return `<div class="empty"><span class="emoji">${emoji}</span><div><strong>${esc(title)}</strong></div><div class="small">${esc(hint)}</div></div>`;
}

async function loadFeed({ refresh = false } = {}) {
  const cached = await cacheGet('feed');
  if (Array.isArray(cached)) S.feed = cached;
  if (!refresh || !ONLINE()) return;
  try {
    await ensureRemoteSession();
    const rows = await rest('feed', { query: 'select=*&order=created_at.desc&limit=60' });
    const pendingPosts = S.feed.filter((p) => p.pending);
    const merged = [...rows, ...pendingPosts.filter((p) => !rows.some((r) => r.id === p.id))];
    S.feed = merged;
    await cacheSet('feed', merged);
    S.lastError = null;
  } catch (error) {
    S.lastError = humanError(error);
  }
}

async function publishPost(body) {
  const text = body.trim();
  if (!text) return toast("Écrivez d'abord un message.");
  if (text.length > MAX_POST_LENGTH) return toast('Message trop long.');
  const post = {
    id: crypto.randomUUID(),
    author_id: S.profile.id,
    author_handle: S.profile.handle,
    author_display_name: S.profile.display_name,
    body: text,
    community_id: null,
    visibility: 'Public',
    created_at: new Date().toISOString(),
    reaction_count: 0,
    comment_count: 0,
    reacted_by_me: false,
    pending: true,
  };
  S.feed = [post, ...S.feed];
  await cacheSet('feed', S.feed);
  await enqueue('post_insert', { id: post.id, author_id: post.author_id, body: text, visibility: 'Public' });
  render();
  await syncNow();
}

async function deletePost(id) {
  const ok = await confirmDialog({ title: 'Supprimer cette publication ?', text: 'Elle sera retirée. La suppression sera aussi envoyée au serveur.', confirmLabel: 'Supprimer', danger: true });
  if (!ok) return;
  S.feed = S.feed.filter((p) => p.id !== id);
  await cacheSet('feed', S.feed);
  await enqueue('post_delete', { id });
  render();
  await syncNow();
}

async function toggleLike(post) {
  post.reacted_by_me = !post.reacted_by_me;
  post.reaction_count = Math.max(0, (post.reaction_count ?? 0) + (post.reacted_by_me ? 1 : -1));
  await cacheSet('feed', S.feed);
  const payload = { post_id: post.id, user_id: S.profile.id, kind: 'tomate' };
  await enqueue(post.reacted_by_me ? 'reaction_add' : 'reaction_remove', payload);
  render();
  await syncNow();
}

// -------------------------------------------------------------- publication

function postView() {
  const post = S.feed.find((p) => p.id === S.param);
  if (!post) {
    return shell(`<div class="card">${emptyState('🤔', 'Publication introuvable sur cet appareil.', 'Elle sera chargée au prochain rafraîchissement du fil.')}</div>`, { title: 'Publication', back: '#/feed' });
  }
  return shell(`
    <div class="card">
      <div class="post" style="padding-top:0">
        <span class="avatar">${initials(post.author_display_name || post.author_handle)}</span>
        <div class="grow">
          <strong>${esc(post.author_display_name || post.author_handle)}</strong>
          <div class="post-meta">@${esc(post.author_handle ?? '')} · ${fmtDate(post.created_at)}</div>
          <div class="post-body" data-body-post="${esc(post.id)}"></div>
          <div class="post-actions">
            <button class="action ${post.reacted_by_me ? 'liked' : ''}" data-act="like" data-id="${esc(post.id)}">
              ${ICON.heart}<span>${post.reaction_count ?? 0}</span>
            </button>
            <span class="action" style="cursor:default">${ICON.comment}<span data-comment-count>${post.comment_count ?? 0}</span></span>
          </div>
        </div>
      </div>
      <div class="divider"></div>
      <form data-form="comment" class="compose" data-post-id="${esc(post.id)}">
        <textarea name="body" maxlength="${MAX_COMMENT_LENGTH}" placeholder="Répondre…" aria-label="Commentaire" style="min-height:56px"></textarea>
        <div class="row-between">
          <span data-counter="comment">0/${MAX_COMMENT_LENGTH}</span>
          <button class="icon-btn" type="submit" style="color:var(--primary)" aria-label="Envoyer">${ICON.send}</button>
        </div>
      </form>
    </div>
    <div class="section-title"><h2>Commentaires</h2></div>
    <div class="card" style="padding:4px 16px">
      ${S.comments.length === 0 ? emptyState('💬', 'Aucun commentaire.', 'Soyez le premier à répondre.') : S.comments.map(commentItem).join('')}
    </div>
  `, { title: 'Publication', back: '#/feed' });
}

function commentItem(comment) {
  return `<div class="post">
    <span class="avatar" style="width:36px;height:36px;font-size:13px">${initials(comment.author_display_name || comment.author_handle)}</span>
    <div class="grow">
      <div class="row wrap" style="gap:8px">
        <strong style="font-size:14px">${esc(comment.author_display_name || comment.author_handle || 'Anonyme')}</strong>
        <span class="post-meta">· ${fmtDate(comment.created_at)}</span>
        ${comment.pending ? '<span class="pending-label">En attente</span>' : ''}
      </div>
      <div class="post-body" style="margin:4px 0 0;font-size:14.5px" data-body-comment="${esc(comment.id)}"></div>
    </div>
  </div>`;
}

async function loadComments(postId) {
  const key = `comments:${postId}`;
  const cached = await cacheGet(key);
  S.comments = Array.isArray(cached) ? cached : [];
  if (!ONLINE()) return;
  try {
    await ensureRemoteSession();
    const rows = await rest('post_comments', {
      query: `select=*,author:profiles(handle,display_name)&post_id=eq.${postId}&deleted_at=is.null&order=created_at.asc&limit=100`,
    });
    const mapped = rows.map((row) => ({
      ...row,
      author_handle: row.author?.handle ?? row.author_handle ?? '',
      author_display_name: row.author?.display_name ?? row.author_display_name ?? '',
    }));
    const pending = S.comments.filter((c) => c.pending);
    S.comments = [...mapped, ...pending.filter((c) => !mapped.some((m) => m.id === c.id))];
    await cacheSet(key, S.comments);
  } catch {
    // The cached comments are still a valid, honest view.
  }
}

async function addComment(postId, body) {
  const text = body.trim();
  if (!text) return toast("Écrivez d'abord un commentaire.");
  const comment = {
    id: crypto.randomUUID(),
    post_id: postId,
    author_id: S.profile.id,
    author_handle: S.profile.handle,
    author_display_name: S.profile.display_name,
    body: text,
    created_at: new Date().toISOString(),
    pending: true,
  };
  S.comments = [...S.comments, comment];
  await cacheSet(`comments:${postId}`, S.comments);
  const post = S.feed.find((p) => p.id === postId);
  if (post) {
    post.comment_count = (post.comment_count ?? 0) + 1;
    await cacheSet('feed', S.feed);
  }
  await enqueue('comment_insert', { id: comment.id, post_id: postId, author_id: comment.author_id, body: text });
  render();
  await syncNow();
}

// --------------------------------------------------------------- messagerie

function messagesView() {
  return shell(`
    ${S.lastError ? notice(S.lastError, 'error', true) : ''}
    <div class="section-title"><h2>Conversations</h2>
      <button class="btn btn-soft" data-act="new-conversation">${ICON.plus} Nouvelle</button>
    </div>
    <div class="card" style="padding:4px 16px">
      ${S.conversations.length === 0
        ? emptyState('✉️', 'Aucune conversation.', 'Démarrez une discussion avec un pseudo TOMATE.')
        : S.conversations.map(conversationItem).join('')}
    </div>
    ${notice("Les messages restent sur l'appareil et partent dès que le réseau le permet. Le chiffrement de bout en bout n'est pas encore implémenté : l'interface ne le prétend pas.", 'info')}
  `, { actions: `<button class="icon-btn" data-act="refresh-messages" aria-label="Actualiser">${ICON.refresh}</button>` });
}

function conversationItem(conversation) {
  return `<button class="list-row" data-act="open-chat" data-id="${esc(conversation.id)}">
    <span class="avatar">${initials(conversation.title || 'Conversation')}</span>
    <span class="grow">
      <span class="title">${esc(conversation.title || (conversation.is_group ? 'Groupe' : 'Conversation'))}</span>
      <span class="sub">${fmtDate(conversation.last_message_at)}</span>
    </span>
  </button>`;
}

function chatView() {
  const conversation = S.conversations.find((c) => c.id === S.param);
  return shell(`
    ${S.lastError ? notice(S.lastError, 'error', true) : ''}
    <div class="card" style="min-height:40vh;display:flex;flex-direction:column;gap:8px;padding:14px">
      ${S.messages.length === 0 ? emptyState('💬', 'Aucun message.', 'Écrivez le premier message.') : S.messages.map(messageItem).join('')}
    </div>
    <form data-form="send-message" data-conversation="${esc(S.param)}" class="card" style="margin-top:12px;display:flex;gap:10px;align-items:flex-end">
      <textarea class="textarea" name="body" maxlength="${MAX_MESSAGE_LENGTH}" placeholder="Message…" aria-label="Message" style="min-height:52px"></textarea>
      <button class="icon-btn" type="submit" style="color:var(--primary);flex:none" aria-label="Envoyer">${ICON.send}</button>
    </form>
  `, { title: conversation?.title || 'Conversation', back: '#/messages' });
}

function messageItem(message) {
  const mine = message.sender_id === S.profile?.id;
  return `<div style="align-self:${mine ? 'flex-end' : 'flex-start'};max-width:85%;background:${mine ? 'var(--primary)' : 'var(--surface-2)'};color:${mine ? 'var(--on-primary)' : 'var(--text)'};padding:9px 13px;border-radius:16px">
    <div style="white-space:pre-wrap">${esc(message.body)}</div>
    <div class="tiny" style="opacity:.75;margin-top:3px">${fmtDate(message.created_at)}${message.pending ? " · en attente" : ''}</div>
  </div>`;
}

async function loadConversations({ refresh = false } = {}) {
  const cached = await cacheGet('conversations');
  if (Array.isArray(cached)) S.conversations = cached;
  if (!refresh || !ONLINE()) return;
  try {
    const session = await ensureRemoteSession();
    if (!session) return;
    const memberships = await rest('conversation_members', { query: `select=conversation_id&user_id=eq.${session.userId}` });
    const ids = memberships.map((m) => m.conversation_id);
    if (ids.length === 0) {
      S.conversations = [];
    } else {
      S.conversations = await rest('conversations', {
        query: `select=*&id=in.(${ids.join(',')})&order=last_message_at.desc&limit=50`,
      });
    }
    await cacheSet('conversations', S.conversations);
  } catch (error) {
    S.lastError = humanError(error);
  }
}

async function loadMessages(conversationId) {
  const key = `messages:${conversationId}`;
  const cached = await cacheGet(key);
  S.messages = Array.isArray(cached) ? cached : [];
  if (!ONLINE()) return;
  try {
    await ensureRemoteSession();
    const rows = await rest('messages', {
      query: `select=*&conversation_id=eq.${conversationId}&order=created_at.asc&limit=200`,
    });
    const pending = S.messages.filter((m) => m.pending);
    S.messages = [...rows, ...pending.filter((m) => !rows.some((r) => r.id === m.id))];
    await cacheSet(key, S.messages);
  } catch {
    // Cached messages stay readable.
  }
}

async function sendMessage(conversationId, body) {
  const text = body.trim();
  if (!text) return;
  const message = {
    id: crypto.randomUUID(),
    client_id: crypto.randomUUID(),
    conversation_id: conversationId,
    sender_id: S.profile.id,
    body: text,
    created_at: new Date().toISOString(),
    pending: true,
  };
  S.messages = [...S.messages, message];
  await cacheSet(`messages:${conversationId}`, S.messages);
  await enqueue('message_send', message);
  render();
  await syncNow();
}

async function newConversation() {
  const handle = await promptDialog({ title: 'Nouvelle conversation', label: 'Pseudo du destinataire', placeholder: 'antoine' });
  if (!handle) return;
  try {
    const rows = await rest('profiles', { query: `select=id,handle,display_name&handle=eq.${encodeURIComponent(handle.toLowerCase())}` });
    const peer = rows?.[0];
    if (!peer) throw new Error('Aucun profil ne porte ce pseudo.');
    if (peer.id === S.profile.id) throw new Error('Vous ne pouvez pas vous écrire à vous-même.');
    const id = crypto.randomUUID();
    const conversation = {
      id,
      title: peer.display_name,
      is_group: false,
      created_by: S.profile.id,
      created_at: new Date().toISOString(),
      last_message_at: new Date().toISOString(),
      pending: !ONLINE(),
    };
    S.conversations = [conversation, ...S.conversations];
    await cacheSet('conversations', S.conversations);
    await enqueue('conversation_create', { id, title: peer.display_name, is_group: false, created_by: S.profile.id });
    await enqueue('conversation_member_add', { conversation_id: id, user_id: S.profile.id });
    await enqueue('conversation_member_add', { conversation_id: id, user_id: peer.id });
    await syncNow();
    location.hash = `#/chat/${id}`;
    await navigate();
  } catch (error) {
    S.lastError = humanError(error);
    render();
  }
}

// -------------------------------------------------------------- communautés

function communitiesView() {
  return shell(`
    ${S.lastError ? notice(S.lastError, 'error', true) : ''}
    <div class="section-title"><h2>Communautés</h2>
      <button class="btn btn-soft" data-act="create-community">${ICON.plus} Créer</button>
    </div>
    <div class="card" style="padding:4px 16px">
      ${S.communities.length === 0
        ? emptyState('🌍', 'Aucune communauté.', 'Créez-en une ou rejoignez-en une existante.')
        : S.communities.map(communityItem).join('')}
    </div>
    ${notice('La création et l’adhésion fonctionnent aussi hors ligne : elles partent à la reconnexion.', 'info')}
  `, { actions: `<button class="icon-btn" data-act="refresh-communities" aria-label="Actualiser">${ICON.refresh}</button>` });
}

function communityItem(community) {
  const member = S.memberships.has(community.id);
  return `<div class="list-row" style="align-items:flex-start">
    <span class="avatar">${initials(community.name)}</span>
    <div class="grow">
      <button class="btn-link" data-act="open-community" data-id="${esc(community.id)}" style="font-size:15px;padding:0">${esc(community.name)}</button>
      <div class="small muted">${esc(community.description || 'Pas de description.')}</div>
      <div class="tiny muted">${community.member_count ?? 0} membre(s) · ${esc(community.visibility)}</div>
    </div>
    ${member
      ? '<span class="badge ok">Membre</span>'
      : `<button class="btn btn-soft" data-act="join-community" data-id="${esc(community.id)}">Rejoindre</button>`}
  </div>`;
}

async function loadCommunities({ refresh = false } = {}) {
  const cached = await cacheGet('communities');
  if (Array.isArray(cached)) S.communities = cached;
  if (!refresh || !ONLINE()) return;
  try {
    const session = await ensureRemoteSession();
    S.communities = await rest('communities', { query: 'select=*&order=created_at.desc&limit=50' });
    await cacheSet('communities', S.communities);
    if (session) {
      const rows = await rest('community_members', { query: `select=community_id&user_id=eq.${session.userId}` });
      S.memberships = new Set(rows.map((r) => r.community_id));
      await cacheSet('memberships', [...S.memberships]);
    }
  } catch (error) {
    const cachedMemberships = await cacheGet('memberships');
    if (Array.isArray(cachedMemberships)) S.memberships = new Set(cachedMemberships);
    S.lastError = humanError(error);
  }
}

async function createCommunity() {
  const name = await promptDialog({ title: 'Créer une communauté', label: 'Nom (3 à 60 caractères)', placeholder: 'Jardin partagé' });
  if (!name) return;
  if (name.trim().length < 3) return toast('Nom trop court.');
  const slug = name
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
    .slice(0, 40);
  if (!/^[a-z0-9-]{3,40}$/.test(slug)) return toast('Ce nom ne peut pas produire un identifiant valide.');
  const community = {
    id: crypto.randomUUID(),
    name: name.trim(),
    slug,
    description: '',
    owner_id: S.profile.id,
    visibility: 'Public',
    member_count: 1,
    created_at: new Date().toISOString(),
    pending: !ONLINE(),
  };
  S.communities = [community, ...S.communities];
  S.memberships.add(community.id);
  await cacheSet('communities', S.communities);
  await cacheSet('memberships', [...S.memberships]);
  await enqueue('community_create', { id: community.id, name: community.name, slug: community.slug, description: '', owner_id: S.profile.id, visibility: 'Public' });
  await enqueue('community_join', { community_id: community.id, user_id: S.profile.id, role: 'Owner', status: 'Active' });
  render();
  await syncNow();
}

async function joinCommunity(id) {
  const community = S.communities.find((c) => c.id === id);
  if (!community) return;
  S.memberships.add(id);
  community.member_count = (community.member_count ?? 0) + 1;
  await cacheSet('communities', S.communities);
  await cacheSet('memberships', [...S.memberships]);
  await enqueue('community_join', { community_id: id, user_id: S.profile.id, role: 'Member', status: 'Active' });
  render();
  await syncNow();
}

function communityView() {
  const community = S.communities.find((c) => c.id === S.param);
  if (!community) {
    return shell(`<div class="card">${emptyState('🌍', 'Communauté introuvable en cache.', 'Revenez à la liste et actualisez.')}</div>`, { title: 'Communauté', back: '#/communities' });
  }
  const posts = S.feed.filter((p) => p.community_id === community.id);
  return shell(`
    <div class="card">
      <div class="row"><span class="avatar lg">${initials(community.name)}</span>
        <div class="grow"><h2>${esc(community.name)}</h2>
        <div class="small muted">${community.member_count ?? 0} membre(s) · ${esc(community.visibility)}</div></div></div>
      <p class="muted">${esc(community.description || 'Pas de description.')}</p>
      <div class="pill-list">
        <span class="badge">slug : ${esc(community.slug)}</span>
        ${S.memberships.has(community.id) ? '<span class="badge ok">Membre</span>' : `<button class="btn btn-soft" data-act="join-community" data-id="${esc(community.id)}">Rejoindre</button>`}
      </div>
    </div>
    <div class="section-title"><h2>Publications</h2></div>
    <div class="card" style="padding:4px 16px">
      ${posts.length === 0 ? emptyState('📝', 'Aucune publication ici.', 'Les publications de communauté arrivent depuis l’application Android ou la file d’attente.') : posts.map(postItem).join('')}
    </div>
  `, { title: 'Communauté', back: '#/communities' });
}

// ------------------------------------------------------------------- réseau

function capabilities() {
  return {
    secure: window.isSecureContext,
    bluetooth: 'bluetooth' in navigator,
    share: typeof navigator.share === 'function',
    notifications: 'Notification' in window,
    geolocation: 'geolocation' in navigator,
    storage: !!navigator.storage?.estimate,
    wakeLock: 'wakeLock' in navigator,
    backgroundSync: 'serviceWorker' in navigator && 'SyncManager' in window,
  };
}

function networkView() {
  const caps = capabilities();
  const bt = S.btDevice;
  return shell(`
    ${S.lastError ? notice(S.lastError, 'error', true) : ''}
    <div class="card">
      <div class="row-between">
        <div><h2>État du réseau</h2><div class="small muted">Ce que la PWA sait réellement faire.</div></div>
        ${statusChip()}
      </div>
      <div class="grid-2" style="margin-top:14px">
        <div class="stat"><div class="v">${S.queue}</div><div class="k">opération(s) en attente</div></div>
        <div class="stat"><div class="v">${S.lastSync ? fmtDate(S.lastSync) : '—'}</div><div class="k">dernière synchronisation</div></div>
        <div class="stat"><div class="v">${S.feed.length}</div><div class="k">publications en cache</div></div>
        <div class="stat"><div class="v">${S.storage ? S.storage.usageMb + ' Mo' : '—'}</div><div class="k">stockage utilisé</div></div>
      </div>
      <div class="row" style="margin-top:14px">
        <button class="btn btn-primary grow" data-act="sync-now" ${S.syncBusy ? 'disabled' : ''}>${S.syncBusy ? 'Synchronisation…' : 'Synchroniser maintenant'}</button>
      </div>
    </div>

    <div class="section-title"><h2>Autorisations et capacités</h2></div>
    <div class="card">
      ${capRow('Contexte sécurisé (HTTPS)', caps.secure, caps.secure ? 'requis pour le service worker' : 'installez la PWA via HTTPS')}
      ${capRow('Bluetooth (Web Bluetooth)', caps.bluetooth, caps.bluetooth ? 'appairage à la demande, Chrome/Edge Android' : 'non exposé par ce navigateur')}
      ${capRow('Partage système', caps.share, caps.share ? 'partage natif du lien' : 'non disponible')}
      ${capRow('Notifications locales', caps.notifications, caps.notifications ? 'après votre autorisation explicite' : 'non disponible')}
      ${capRow('Géolocalisation', caps.geolocation, caps.geolocation ? 'utilisée seulement pour la météo, sur demande' : 'non disponible')}
      ${capRow('Stockage persistant', caps.storage, caps.storage ? 'estimation du quota' : 'non disponible')}
      ${capRow('Synchronisation en arrière-plan', caps.backgroundSync, caps.backgroundSync ? 'le navigateur peut rejouer la file' : 'non accordée par ce navigateur')}
    </div>

    <div class="section-title"><h2>Tester réellement</h2></div>
    <div class="card col">
      <div class="row-between">
        <div><strong>Bluetooth</strong><div class="small muted">Ouvre le sélecteur du navigateur et affiche l'appareil choisi.</div></div>
        <button class="btn btn-soft" data-act="bt-test" ${caps.bluetooth ? '' : 'disabled'}>Tester</button>
      </div>
      ${bt ? `<div class="notice success">Appareil sélectionné : <strong>${esc(bt.name || 'sans nom')}</strong> (${esc(bt.id)})</div>` : ''}
      <div class="divider"></div>
      <div class="row-between">
        <div><strong>Partager TOMATE</strong><div class="small muted">Utilise le partage natif du système.</div></div>
        <button class="btn btn-soft" data-act="share" ${caps.share ? '' : 'disabled'}>Partager</button>
      </div>
      <div class="divider"></div>
      <div class="row-between">
        <div><strong>Notification de test</strong><div class="small muted">Demande l'autorisation puis affiche une notification locale.</div></div>
        <button class="btn btn-soft" data-act="notify-test" ${caps.notifications ? '' : 'disabled'}>Tester</button>
      </div>
    </div>

    <div class="section-title"><h2>Ce que le navigateur ne permet pas</h2></div>
    <div class="card">
      ${notice("Le mesh TOMATE (BLE en arrière-plan, Wi-Fi Direct, relais multi-sauts entre téléphones) nécessite l'application Android : aucun navigateur ne donne accès à ces fonctions. La PWA ne fait donc jamais semblant de relayer.", 'warning')}
      ${notice("La PWA distribue les contenus TOMATE par Internet et les conserve hors ligne (IndexedDB). Le partage direct entre appareils sans Internet reste une capacité Android.", 'info')}
      ${notice("Les données météo proviennent d'Open-Meteo et sont mises en cache avec leur date : une prévision ancienne est affichée comme ancienne, jamais comme du temps réel.", 'info')}
    </div>
    <div class="section-title"><h2>File d'attente</h2></div>
    <div class="card" id="queue-list"><div class="muted small">Chargement…</div></div>
  `, { actions: `<button class="icon-btn" data-act="refresh" aria-label="Actualiser">${ICON.refresh}</button>` });
}

function capRow(label, ok, detail) {
  return `<div class="settings-row">
    <div><div style="font-weight:600">${esc(label)}</div><div class="tiny muted">${esc(detail)}</div></div>
    <span class="badge ${ok ? 'ok' : 'ko'}">${ok ? 'disponible' : 'indisponible'}</span>
  </div>`;
}

async function renderQueue() {
  const host = document.getElementById('queue-list');
  if (!host) return;
  const ops = await getAll('outbox');
  host.innerHTML = ops.length === 0
    ? '<div class="muted small">Aucune opération en attente.</div>'
    : ops.map((op) => `<div class="row-between" style="padding:8px 0;border-bottom:1px solid var(--border)">
        <span class="small"><strong>${esc(op.kind)}</strong><div class="tiny muted">${fmtDate(new Date(op.createdAt).toISOString())}</div></span>
        <span class="badge warn">en attente</span>
      </div>`).join('');
}

// -------------------------------------------------------------------- météo

function weatherView() {
  const w = S.weather;
  return shell(`
    ${S.lastError ? notice(S.lastError, 'error', true) : ''}
    <div class="card col">
      <form data-form="city" class="row">
        <input class="input grow" name="city" placeholder="Ville (ex. Lyon)" aria-label="Rechercher une ville" />
        <button class="btn btn-soft" type="submit">Chercher</button>
      </form>
      <div class="row wrap">
        <button class="btn btn-soft" data-act="geolocate">Ma position</button>
        <button class="btn btn-soft" data-act="weather-refresh" ${S.weatherBusy || !w ? 'disabled' : ''}>Actualiser</button>
      </div>
      <p class="hint">Source : Open-Meteo. La position n'est demandée qu'après un appui explicite. Sans réseau, la dernière prévision reste consultable avec son ancienneté.</p>
    </div>
    ${w ? weatherDetails(w) : `<div class="card">${emptyState('🌤️', 'Aucune météo en cache.', 'Cherchez une ville ou utilisez votre position, une fois en ligne.')}</div>`}
  `, { title: 'Météo', back: '#/feed' });
}

function weatherDetails(w) {
  const [label, emoji] = describeCode(w.current?.weather_code);
  const hourly = (w.hourly?.time ?? []).slice(0, 12).map((time, index) => {
    const [hourLabel, hourEmoji] = describeCode(w.hourly.code?.[index]);
    return `<div class="hour"><div class="tiny muted">${new Date(time).toLocaleTimeString('fr-FR', { hour: '2-digit' })}</div>
      <div style="font-size:20px">${hourEmoji}</div>
      <div>${Math.round(w.hourly.temperature?.[index] ?? 0)}°</div>
      <div class="tiny muted">${w.hourly.precipitation?.[index] ?? 0}%</div></div>`;
  }).join('');
  const daily = (w.daily?.time ?? []).map((time, index) => {
    const [, dayEmoji] = describeCode(w.daily.weather_code?.[index]);
    return `<div class="settings-row">
      <div><strong>${new Date(time).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'short' })}</strong>
      <div class="tiny muted">${dayEmoji} ${esc(describeCode(w.daily.weather_code?.[index])[0])}</div></div>
      <div>${Math.round(w.daily.temperature_2m_max?.[index] ?? 0)}° / <span class="muted">${Math.round(w.daily.temperature_2m_min?.[index] ?? 0)}°</span></div>
    </div>`;
  }).join('');
  return `
    <div class="weather-hero">
      <div>
        <div class="tiny muted">${esc(w.place?.label ?? 'Position')} · ${weatherAge(w.fetchedAt)}</div>
        <div class="row" style="gap:10px;align-items:baseline"><span class="weather-temp">${Math.round(w.current?.temperature_2m ?? 0)}°</span>
        <span class="muted">${esc(label)}</span></div>
        <div class="small muted">Ressenti ${Math.round(w.current?.apparent_temperature ?? 0)}° · humidité ${w.current?.relative_humidity_2m ?? '—'}% · vent ${Math.round(w.current?.wind_speed_10m ?? 0)} km/h</div>
      </div>
      <span class="weather-emoji">${emoji}</span>
    </div>
    <div class="section-title"><h2>Prochaines heures</h2></div>
    <div class="hourly">${hourly || '<span class="muted small">Pas de prévision horaire.</span>'}</div>
    <div class="section-title"><h2>Prochains jours</h2></div>
    <div class="card" style="padding:6px 16px">${daily || '<span class="muted small">Pas de prévision quotidienne.</span>'}</div>
    <div class="card" style="margin-top:12px">
      <div class="tiny muted">Prévision récupérée ${weatherAge(w.fetchedAt)}${ONLINE() ? '' : ' (hors ligne)'}. Lever ${fmtClock(w.daily?.sunrise?.[0])} · coucher ${fmtClock(w.daily?.sunset?.[0])} · UV max ${w.daily?.uv_index_max?.[0] ?? '—'}.</div>
    </div>`;
}

function fmtClock(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
}

async function refreshWeather(place) {
  S.weatherBusy = true;
  render();
  try {
    S.weather = place && place.latitude
      ? await fetchWeather(place.latitude, place.longitude, place.label)
      : await fetchWeather(S.weather.place.latitude, S.weather.place.longitude, S.weather.place.label);
    S.lastError = null;
  } catch (error) {
    S.lastError = humanError(error);
  } finally {
    S.weatherBusy = false;
    render();
  }
}

async function geolocate() {
  if (!('geolocation' in navigator)) return toast('Géolocalisation indisponible sur ce navigateur.');
  toast('Demande de position…');
  navigator.geolocation.getCurrentPosition(
    async (position) => {
      const { latitude, longitude } = position.coords;
      await refreshWeather({ latitude, longitude, label: 'Ma position' });
    },
    (error) => {
      S.lastError = `Position refusée ou indisponible (${error.message}). Vous pouvez chercher une ville.`;
      render();
    },
    { timeout: 12000, maximumAge: 600000 },
  );
}

// -------------------------------------------------------------------- profil

function profileView() {
  const profile = S.profile;
  return shell(`
    ${S.lastError ? notice(S.lastError, 'error', true) : ''}
    <div class="card">
      <div class="row">
        <span class="avatar lg">${initials(profile.display_name)}</span>
        <div class="grow">
          <h2>${esc(profile.display_name)}</h2>
          <div class="small muted">@${esc(profile.handle)} · ${esc(profile.visibility ?? 'Public')}</div>
        </div>
        <button class="icon-btn" data-act="edit-profile" aria-label="Modifier">${ICON.settings}</button>
      </div>
      <p class="muted" style="margin-bottom:4px">${esc(profile.bio || 'Pas de bio.')}</p>
      ${S.session?.local ? notice("Compte local : il sera relié à Supabase automatiquement dès qu'une connexion sera disponible. Le pseudo sera alors vérifié.", 'info') : ''}
    </div>
    <div class="section-title"><h2>Mes publications</h2></div>
    <div class="card" style="padding:4px 16px">
      ${(() => {
        const mine = S.feed.filter((p) => p.author_id === profile.id);
        return mine.length === 0 ? emptyState('📝', 'Aucune publication.', 'Vos messages apparaîtront ici.') : mine.map(postItem).join('');
      })()}
    </div>
    <div class="card" style="margin-top:12px">
      <div class="settings-row"><div><strong>Paramètres</strong><div class="tiny muted">Thème, installation, notifications, stockage</div></div>
        <button class="btn btn-soft" data-act="nav" data-route="#/settings">Ouvrir</button></div>
      <div class="settings-row"><div><strong>Synchronisation</strong><div class="tiny muted">${S.queue} opération(s) en attente</div></div>
        <button class="btn btn-soft" data-act="sync-now">Synchroniser</button></div>
      <div class="settings-row"><div><strong>Se déconnecter</strong><div class="tiny muted">Supprime la session et les données locales</div></div>
        <button class="btn btn-danger" data-act="sign-out">Se déconnecter</button></div>
    </div>
  `, { actions: `<button class="icon-btn" data-act="nav" data-route="#/settings" aria-label="Paramètres">${ICON.settings}</button>` });
}

async function editProfile() {
  const handle = await promptDialog({ title: 'Modifier le profil', label: 'Pseudo', value: S.profile.handle });
  if (handle === null) return;
  if (!/^[a-z0-9_]{3,20}$/.test(handle.toLowerCase())) return toast('Pseudo invalide.');
  const display = await promptDialog({ title: 'Modifier le profil', label: 'Nom affiché', value: S.profile.display_name });
  if (display === null) return;
  const bio = await promptDialog({ title: 'Modifier le profil', label: 'Bio', value: S.profile.bio ?? '' });
  if (bio === null) return;
  const profile = { ...S.profile, handle: handle.toLowerCase(), display_name: display.trim(), bio: bio.trim() };
  S.profile = profile;
  await setItem('kv', 'profile', profile);
  await enqueue('profile_upsert', { id: profile.id, handle: profile.handle, display_name: profile.display_name, bio: profile.bio, visibility: profile.visibility });
  render();
  await syncNow();
}

async function signOut() {
  const ok = await confirmDialog({
    title: 'Se déconnecter ?',
    text: 'La session, le profil et toutes les données locales (publications en attente, caches) seront supprimés de cet appareil. Sans code de récupération, le compte ne pourra pas être restauré.',
    confirmLabel: 'Se déconnecter',
    danger: true,
  });
  if (!ok) return;
  await clearEverything();
  await clearSession();
  S.session = null;
  S.profile = null;
  S.feed = [];
  S.conversations = [];
  S.messages = [];
  S.communities = [];
  S.comments = [];
  S.weather = null;
  S.queue = 0;
  S.route = 'welcome';
  location.hash = '#/welcome';
  render();
  toast('Déconnecté. Les données locales ont été supprimées.');
}

// ---------------------------------------------------------------- paramètres

function settingsView() {
  const caps = capabilities();
  return shell(`
    <div class="card">
      <h2 style="margin-bottom:10px">Apparence</h2>
      <div class="pill-list">
        ${['auto', 'light', 'dark'].map((mode) => `<button class="btn ${S.theme === mode ? 'btn-primary' : 'btn-soft'}" data-act="theme" data-mode="${mode}">
          ${mode === 'auto' ? 'Système' : mode === 'light' ? 'Clair' : 'Sombre'}</button>`).join('')}
      </div>
    </div>

    <div class="card">
      <h2 style="margin-bottom:10px">Installation</h2>
      <p class="small muted">TOMATE est une PWA : elle s'installe depuis le navigateur et fonctionne hors ligne après le premier chargement.</p>
      <button class="btn btn-primary btn-block" data-act="install" ${S.installPrompt ? '' : 'disabled'}>
        ${ICON.download} ${S.installPrompt ? "Installer TOMATE" : 'Installation indisponible ici'}
      </button>
      <p class="hint">Sur iPhone : Partager → « Sur l'écran d'accueil ». Sur ordinateur : icône d'installation dans la barre d'adresse.</p>
    </div>

    <div class="card">
      <h2 style="margin-bottom:10px">Notifications et stockage</h2>
      <div class="settings-row"><div><strong>Notifications</strong><div class="tiny muted">Autorisation demandée au moment de l'action</div></div>
        <button class="btn btn-soft" data-act="notify-test" ${caps.notifications ? '' : 'disabled'}>Tester</button></div>
      <div class="settings-row"><div><strong>Stockage</strong><div class="tiny muted">${S.storage ? `${S.storage.usageMb} Mo utilisés sur ${S.storage.quotaMb} Mo` : 'estimation indisponible'}</div></div>
        <button class="btn btn-soft" data-act="storage">Estimer</button></div>
      <div class="settings-row"><div><strong>Vider le cache de contenu</strong><div class="tiny muted">Conserve le compte et la file d'attente</div></div>
        <button class="btn btn-danger" data-act="clear-cache">Vider</button></div>
    </div>

    <div class="card">
      <h2 style="margin-bottom:10px">Limites annoncées</h2>
      <ul class="small muted" style="padding-left:18px;line-height:1.6">
        <li>Pas de mesh BLE / Wi-Fi Direct ni de relais en arrière-plan dans un navigateur : l'app Android reste la référence.</li>
        <li>Chiffrement de bout en bout : absent. Aucun libellé ne le prétend.</li>
        <li>Récupération de compte : non disponible, aucun code ne fonctionne encore.</li>
        <li>Météo : Open-Meteo, mise en cache datée.</li>
        <li>Aucune notification push : seulement des notifications locales.</li>
      </ul>
    </div>

    <div class="card">
      <div class="row-between"><div><strong>Se déconnecter</strong><div class="tiny muted">Efface aussi les données locales</div></div>
        <button class="btn btn-danger" data-act="sign-out">Se déconnecter</button></div>
    </div>
    <p class="tiny muted center" style="margin-top:16px">TOMATE ${APP_VERSION} · client web · backend Supabase</p>
  `, { title: 'Paramètres', back: '#/profile' });
}

async function estimateStorage() {
  if (!navigator.storage?.estimate) return toast('Estimation indisponible sur ce navigateur.');
  const { usage = 0, quota = 0 } = await navigator.storage.estimate();
  S.storage = { usageMb: (usage / 1048576).toFixed(1), quotaMb: (quota / 1048576).toFixed(0) };
  render();
}

async function applyTheme(mode) {
  S.theme = mode ?? 'auto';
  if (S.theme === 'auto') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.dataset.theme = S.theme;
  await setItem('kv', 'theme', S.theme);
}

async function notifyTest() {
  if (!('Notification' in window)) return toast('Notifications indisponibles.');
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return toast('Autorisation refusée : aucune notification ne sera affichée.');
  new Notification('TOMATE', { body: 'Les notifications locales fonctionnent.', icon: '/icons/icon-192.png' });
  toast('Notification envoyée.');
}

// -------------------------------------------------------------- synchro/état

async function syncNow({ silent = false } = {}) {
  S.syncBusy = true;
  if (S.route === 'network') render();
  try {
    if (S.session?.local && ONLINE()) {
      const result = await linkLocalAccount(S.profile);
      if (result.linked) {
        S.session = await getSession();
        if (result.profile) S.profile = result.profile;
        toast('Compte relié à Supabase.');
      }
    }
    const result = await flushQueue();
    S.queue = result.remaining ?? (await pendingCount());
    // Offline is not an error: the pending notice explains it. Only a failure while the browser
    // believes it is online deserves an error banner.
    if (result.lastError && navigator.onLine) S.lastError = result.lastError;
    else if (result.lastError) S.lastError = null;
    else if (result.flushed > 0) {
      S.lastSync = Date.now();
      if (!silent) toast(`${result.flushed} opération(s) synchronisée(s).`);
      if ('Notification' in window && Notification.permission === 'granted') {
        new Notification('TOMATE', { body: `${result.flushed} opération(s) synchronisée(s).`, icon: '/icons/icon-192.png' });
      }
    }
    // Pending flags are source-of-truth in IndexedDB: reload them from the cache after a flush
    // instead of guessing from in-memory copies.
    const freshFeed = await cacheGet('feed');
    if (Array.isArray(freshFeed)) S.feed = freshFeed;
    if (S.route === 'post' && S.param) {
      const freshComments = await cacheGet(`comments:${S.param}`);
      if (Array.isArray(freshComments)) S.comments = freshComments;
    }
    if (S.route === 'chat' && S.param) {
      const freshMessages = await cacheGet(`messages:${S.param}`);
      if (Array.isArray(freshMessages)) S.messages = freshMessages;
    }
  } finally {
    S.syncBusy = false;
    await refreshCounts();
    if (['feed', 'network', 'profile', 'settings'].includes(S.route)) render();
  }
}

async function refreshCounts() {
  S.queue = await pendingCount();
  if (navigator.storage?.estimate) {
    try {
      const { usage = 0, quota = 0 } = await navigator.storage.estimate();
      S.storage = { usageMb: (usage / 1048576).toFixed(1), quotaMb: (quota / 1048576).toFixed(0) };
    } catch {
      S.storage = null;
    }
  }
}

// ------------------------------------------------------------------- routage

function parseRoute() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [route, ...rest] = raw.split('/');
  return { route: route || 'feed', param: rest.length ? decodeURIComponent(rest.join('/')) : null };
}

async function navigate() {
  const { route, param } = parseRoute();
  S.route = route;
  S.param = param;
  if (!S.profile) {
    S.route = 'welcome';
    render();
    return;
  }
  switch (S.route) {
    case 'feed':
      render();
      await loadFeed({ refresh: true });
      S.queue = await pendingCount();
      if (ONLINE()) await syncNow({ silent: true });
      break;
    case 'post':
      render();
      await loadComments(S.param);
      break;
    case 'messages':
      render();
      await loadConversations({ refresh: true });
      break;
    case 'chat':
      render();
      await loadMessages(S.param);
      break;
    case 'communities':
      render();
      await loadCommunities({ refresh: true });
      break;
    case 'community':
      render();
      break;
    case 'weather':
      render();
      break;
    case 'network':
      render();
      await refreshCounts();
      await renderQueue();
      break;
    case 'profile':
    case 'settings':
      render();
      await refreshCounts();
      break;
    default:
      S.route = 'feed';
      render();
  }
  render();
}

function view() {
  switch (S.route) {
    case 'welcome': return welcomeView();
    case 'post': return postView();
    case 'messages': return messagesView();
    case 'chat': return chatView();
    case 'communities': return communitiesView();
    case 'community': return communityView();
    case 'network': return networkView();
    case 'weather': return weatherView();
    case 'profile': return profileView();
    case 'settings': return settingsView();
    default: return feedView();
  }
}

function render() {
  if (S.booting) {
    app.innerHTML = '<div class="boot"><div class="boot-logo">TOMATE</div><p class="muted">Chargement…</p></div>';
    return;
  }
  if (!S.profile) {
    app.innerHTML = welcomeView();
    return;
  }
  app.innerHTML = view();
  hydrateBodies();
  if (S.route === 'network') renderQueue();
}

/**
 * User text is never interpolated into HTML: it is attached as textContent to the element that
 * represents its own post or comment, which rules out injection through API content.
 */
function hydrateBodies() {
  document.querySelectorAll('[data-body-post]').forEach((node) => {
    const post = S.feed.find((p) => p.id === node.dataset.bodyPost);
    if (post) node.textContent = post.body;
  });
  document.querySelectorAll('[data-body-comment]').forEach((node) => {
    const comment = S.comments.find((c) => c.id === node.dataset.bodyComment);
    if (comment) node.textContent = comment.body;
  });
}

// ------------------------------------------------------------------- actions

document.addEventListener('click', async (event) => {
  const trigger = event.target.closest('[data-act]');
  if (!trigger) return;
  const act = trigger.dataset.act;
  try {
    switch (act) {
      case 'nav': {
        const target = trigger.dataset.route;
        if (location.hash === target) await navigate();
        else location.hash = target;
        break;
      }
      case 'refresh':
        await loadFeed({ refresh: true });
        await syncNow({ silent: true });
        render();
        break;
      case 'refresh-messages':
        await loadConversations({ refresh: true });
        render();
        break;
      case 'refresh-communities':
        await loadCommunities({ refresh: true });
        render();
        break;
      case 'open-post':
        location.hash = `#/post/${trigger.dataset.id}`;
        break;
      case 'open-chat':
        location.hash = `#/chat/${trigger.dataset.id}`;
        break;
      case 'open-community':
        location.hash = `#/community/${trigger.dataset.id}`;
        break;
      case 'like': {
        const post = S.feed.find((p) => p.id === trigger.dataset.id);
        if (post) await toggleLike(post);
        break;
      }
      case 'delete-post':
        await deletePost(trigger.dataset.id);
        break;
      case 'new-conversation':
        await newConversation();
        break;
      case 'create-community':
        await createCommunity();
        break;
      case 'join-community':
        await joinCommunity(trigger.dataset.id);
        break;
      case 'sync-now':
        await syncNow();
        break;
      case 'dismiss-error':
        S.lastError = null;
        render();
        break;
      case 'sign-out':
        await signOut();
        break;
      case 'edit-profile':
        await editProfile();
        break;
      case 'theme':
        await applyTheme(trigger.dataset.mode);
        render();
        break;
      case 'install':
        if (S.installPrompt) {
          S.installPrompt.prompt();
          const choice = await S.installPrompt.userChoice;
          if (choice.outcome === 'accepted') toast('TOMATE installée.');
          S.installPrompt = null;
          render();
        }
        break;
      case 'notify-test':
        await notifyTest();
        break;
      case 'storage':
        await estimateStorage();
        break;
      case 'clear-cache':
        if (await confirmDialog({ title: 'Vider le cache ?', text: 'Les publications, messages et météo en cache seront retéléchargés. Le compte et la file d\'attente sont conservés.', confirmLabel: 'Vider' })) {
          await clearStore('cache');
          S.feed = [];
          S.comments = [];
          S.conversations = [];
          S.messages = [];
          S.communities = [];
          S.weather = null;
          toast('Cache vidé.');
          render();
        }
        break;
      case 'bt-test': {
        if (!('bluetooth' in navigator)) return toast('Web Bluetooth indisponible sur ce navigateur.');
        try {
          const device = await navigator.bluetooth.requestDevice({ acceptAllDevices: true });
          S.btDevice = { name: device.name, id: device.id };
          toast(`Appareil sélectionné : ${device.name || 'sans nom'}`);
        } catch (error) {
          S.lastError = `Sélection Bluetooth annulée ou refusée : ${error.message}`;
        }
        render();
        break;
      }
      case 'share':
        if (navigator.share) {
          try {
            await navigator.share({ title: 'TOMATE', text: 'Rejoignez TOMATE, le réseau social hors ligne.', url: location.origin });
          } catch {
            // The user dismissed the share sheet: nothing to report.
          }
        }
        break;
      case 'geolocate':
        await geolocate();
        break;
      case 'weather-refresh':
        if (S.weather) await refreshWeather(S.weather.place);
        break;
      default:
        break;
    }
  } catch (error) {
    S.lastError = humanError(error);
    render();
  }
});

document.addEventListener('input', (event) => {
  const field = event.target;
  if (!field.matches('textarea[maxlength]')) return;
  const form = field.closest('form');
  const counter = form?.querySelector('[data-counter]');
  if (counter) counter.textContent = `${field.value.length}/${field.maxLength}`;
});

document.addEventListener('submit', async (event) => {
  const form = event.target.closest('form[data-form]');
  if (!form) return;
  event.preventDefault();
  const data = new FormData(form);
  try {
    switch (form.dataset.form) {
      case 'onboarding':
        await createProfile({
          handle: data.get('handle'),
          display: data.get('display'),
          bio: data.get('bio') ?? '',
          visibility: data.get('visibility') ?? 'Public',
        });
        break;
      case 'publish':
        await publishPost(String(data.get('body') ?? ''));
        form.reset();
        break;
      case 'comment':
        await addComment(form.dataset.postId, String(data.get('body') ?? ''));
        form.reset();
        break;
      case 'send-message':
        await sendMessage(form.dataset.conversation, String(data.get('body') ?? ''));
        form.reset();
        break;
      case 'city': {
        const city = String(data.get('city') ?? '').trim();
        if (!city) return;
        const results = await searchCity(city);
        if (results.length === 0) return toast('Aucune ville trouvée.');
        const place = results[0];
        S.lastError = null;
        await refreshWeather(place);
        break;
      }
      default:
        break;
    }
  } catch (error) {
    S.lastError = humanError(error);
    render();
  }
});

// --------------------------------------------------------------- événements

window.addEventListener('online', async () => {
  S.online = true;
  toast('Réseau rétabli.');
  render();
  await syncNow({ silent: true });
  if (S.route === 'feed') await loadFeed({ refresh: true });
  render();
});

window.addEventListener('offline', () => {
  S.online = false;
  toast('Hors ligne : TOMATE continue en local.');
  render();
});

window.addEventListener('beforeinstallprompt', (event) => {
  event.preventDefault();
  S.installPrompt = event;
  if (S.route === 'settings') render();
});

window.addEventListener('hashchange', () => navigate());

async function boot() {
  try {
    await applyTheme(await getItem('kv', 'theme'));
    S.session = await ensureActor();
    S.profile = await getItem('kv', 'profile');
    S.weather = await cachedWeather();
    const cachedFeed = await cacheGet('feed');
    if (Array.isArray(cachedFeed)) S.feed = cachedFeed;
    const cachedMemberships = await cacheGet('memberships');
    if (Array.isArray(cachedMemberships)) S.memberships = new Set(cachedMemberships);
    await refreshCounts();
  } catch (error) {
    S.lastError = humanError(error);
  } finally {
    S.booting = false;
    if (!S.profile) S.route = 'welcome';
    await navigate();
    if (navigator.onLine) await syncNow({ silent: true });
  }
}

if ('serviceWorker' in navigator) {
  window.addEventListener('load', async () => {
    try {
      const registration = await navigator.serviceWorker.register('/sw.js');
      registration.addEventListener('updatefound', () => {
        toast('Mise à jour disponible : rechargez pour l’appliquer.');
      });
    } catch {
      // The app still works without a service worker (no offline cache).
    }
  });
}

boot();
