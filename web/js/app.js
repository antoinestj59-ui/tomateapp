// TOMATE — PWA. Un seul point d'entrée : routes par hash, rendu DOM, hors ligne réel.
import {
  HttpError,
  attachCredentials,
  clearSession,
  ensureActor,
  ensureRemoteSession,
  fetchMyProfile,
  getSession,
  handleAvailable,
  isLocalId,
  linkLocalAccount,
  rememberEmail,
  rest,
  signInWithPassword,
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
  CALL_PREFIX,
  CallSession,
  decodeEvent,
  decodePoll,
  collectSignals,
  packSignal,
  encodeEvent,
  encodePoll,
  encodeVote,
  formatEventDate,
  isVote,
  localInputToIso,
  tallyPoll,
} from './extras.js';
import {
  APP_VERSION,
  MAX_COMMENT_LENGTH,
  MAX_MESSAGE_LENGTH,
  MAX_POST_LENGTH,
  MAX_STORY_LENGTH,
  SUPABASE_KEY,
  SUPABASE_URL,
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
  stories: [],
  comments: [],
  conversations: [],
  messages: [],
  streaks: {},
  communities: [],
  memberships: new Set(),
  weather: null,
  weatherBusy: false,
  syncBusy: false,
  installPrompt: null,
  btDevice: null,
  theme: 'auto',
  storage: null,
  draft: '',
  draftCommunity: {},
  postMedia: null,
  chatMedia: null,
  mediaUrls: {},
  communityMembers: {},
  communityFilter: '',
  search: { q: '', results: [], busy: false, error: null },
  notifications: [],
  notifActors: {},
  unread: 0,
  notificationsReady: null,
  notificationsError: null,
  authEmail: '',
  rsvps: {},
  call: null,
  callWatch: null,
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

/**
 * Participant picker. Rows come from the `profiles` table, so a suggestion always exists on the
 * server; nothing is fabricated locally. Returns the chosen profiles, or null when cancelled.
 */
function contactPickerDialog({ title = 'Nouvelle conversation' } = {}) {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.className = 'contact-dialog';
    dialog.innerHTML = `
      <h2>${esc(title)}</h2>
      <p class="tiny muted" style="margin-bottom:12px">Choisissez une ou plusieurs personnes de ce serveur. Aucun contact n'est inventé.</p>
      <div class="field">
        <label for="contact-search">Rechercher</label>
        <input class="input" id="contact-search" placeholder="Pseudo ou nom" autocomplete="off" enterkeyhint="search" />
      </div>
      <div class="contact-list" id="contact-list" aria-live="polite"></div>
      <div class="row" style="justify-content:flex-end;margin-top:18px">
        <button class="btn btn-soft" data-choice="cancel">Annuler</button>
        <button class="btn btn-primary" data-choice="ok" disabled>Créer</button>
      </div>`;
    document.body.appendChild(dialog);
    dialog.showModal();

    const list = dialog.querySelector('#contact-list');
    const okBtn = dialog.querySelector('[data-choice="ok"]');
    const search = dialog.querySelector('#contact-search');
    const chosen = new Map();
    let timer = null;
    let seq = 0;

    const paintButton = () => {
      okBtn.disabled = chosen.size === 0;
      okBtn.textContent =
        chosen.size === 0 ? 'Créer'
          : chosen.size === 1 ? 'Discuter' : `Créer le groupe (${chosen.size})`;
    };

    const renderRows = (rows) => {
      if (rows.length === 0) {
        list.innerHTML = `<p class="tiny muted" style="padding:14px 4px">${
          search.value.trim() ? 'Aucun profil ne correspond.' : 'Aucun autre profil sur ce serveur pour le moment.'
        }</p>`;
        return;
      }
      list.innerHTML = rows
        .map((p) => {
          const name = p.display_name || p.handle;
          const on = chosen.has(p.id);
          return `<button class="contact-row${on ? ' on' : ''}" data-id="${esc(p.id)}"
              data-handle="${esc(p.handle)}" data-name="${esc(name)}"
              role="option" aria-selected="${on}">
            <span class="avatar sm">${esc(name.slice(0, 2).toUpperCase())}</span>
            <span class="contact-meta"><strong>${esc(name)}</strong><span class="tiny muted">@${esc(p.handle)}</span></span>
            <span class="contact-check" aria-hidden="true">${on ? '✓' : ''}</span>
          </button>`;
        })
        .join('');
    };

    list.addEventListener('click', (event) => {
      const row = event.target.closest('.contact-row');
      if (!row) return;
      const person = { id: row.dataset.id, handle: row.dataset.handle, display_name: row.dataset.name };
      if (chosen.has(person.id)) chosen.delete(person.id);
      else chosen.set(person.id, person);
      row.classList.toggle('on', chosen.has(person.id));
      row.setAttribute('aria-selected', String(chosen.has(person.id)));
      row.querySelector('.contact-check').textContent = chosen.has(person.id) ? '✓' : '';
      paintButton();
    });

    const load = async () => {
      const q = search.value.trim().replace(/^@/, '').replace(/[*,()]/g, '');
      const mine = ++seq;
      list.innerHTML = '<p class="tiny muted" style="padding:14px 4px">Recherche…</p>';
      try {
        const filter = q
          ? `&or=(handle.ilike.*${q}*,display_name.ilike.*${q}*)`
          : '';
        const rows = await rest('profiles', {
          query: `select=id,handle,display_name&order=handle.asc&limit=20&id=neq.${S.profile.id}${filter}`,
        });
        if (mine !== seq) return;
        renderRows(rows ?? []);
      } catch (error) {
        if (mine !== seq) return;
        list.innerHTML = `<p class="tiny" style="padding:14px 4px;color:var(--danger)">${esc(humanError(error))}</p>`;
      }
    };

    search.addEventListener('input', () => {
      clearTimeout(timer);
      timer = setTimeout(load, 250);
    });

    const finish = (result) => {
      clearTimeout(timer);
      dialog.close();
      dialog.remove();
      resolve(result);
    };

    dialog.addEventListener('click', (event) => {
      const choice = event.target.closest('[data-choice]')?.dataset.choice;
      if (choice === 'cancel') finish(null);
      if (choice === 'ok' && chosen.size > 0) finish([...chosen.values()]);
    });
    dialog.addEventListener('cancel', () => finish(null));

    paintButton();
    load();
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
  qr: svg('<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h3v3h-3zM20 14v2M14 20h2M18 18h3v3h-3z"/>'),
  plus: svg('<path d="M12 5v14M5 12h14"/>'),
  download: svg('<path d="M12 3v12"/><path d="M7 10l5 5 5-5"/><path d="M4 21h16"/>'),
  weather: svg('<circle cx="8" cy="8" r="3"/><path d="M8 2v1.5M2 8h1.5M3.8 3.8l1 1M12.2 3.8l-1 1"/><path d="M9 19h9a4 4 0 0 0 0-8 5.5 5.5 0 0 0-10.4 1.5A3.5 3.5 0 0 0 9 19Z"/>'),
  close: svg('<path d="M6 6l12 12M18 6 6 18"/>'),
  people: svg('<circle cx="12" cy="8" r="3.2"/><path d="M6 20c0-3 2.7-5 6-5s6 2 6 5"/>'),
  search: svg('<circle cx="11" cy="11" r="7"/><path d="m16.5 16.5 4.5 4.5"/>'),
  image: svg('<rect x="3" y="4" width="18" height="16" rx="3"/><circle cx="9" cy="10" r="2"/><path d="m5 18 5-5 3 3 3-3 3 3"/>'),
  video: svg('<rect x="3" y="6" width="13" height="12" rx="3"/><path d="m16 11 5-3v8l-5-3Z"/>'),
  paperclip: svg('<path d="M21.4 11.05 12.3 20.2a6 6 0 0 1-8.5-8.5l8.5-8.5a4 4 0 0 1 5.7 5.7l-8.5 8.5a2 2 0 0 1-2.8-2.8l7.8-7.8"/>'),
  link: svg('<path d="M10 13a5 5 0 0 0 7.5.5l2-2a5 5 0 0 0-7-7l-1 1"/><path d="M14 11a5 5 0 0 0-7.5-.5l-2 2a5 5 0 0 0 7 7l1-1"/>'),
  bell: svg('<path d="M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/>'),
  chart: svg('<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>'),
  phone: svg('<path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 2 .7 2.9a2 2 0 0 1-.5 2.1L8.1 9.9a16 16 0 0 0 6 6l1.2-1.2a2 2 0 0 1 2.1-.5c.9.3 1.9.6 2.9.7a2 2 0 0 1 1.7 2Z"/>'),
  camera: svg('<rect x="2" y="6" width="14" height="12" rx="3"/><path d="m16 11 6-3.5v9L16 13Z"/>'),
};

/** Marque TOMATE : une tomate sobre, dessinée dans la couleur d'accent. */
const LOGO = `<svg class="logo-mark" viewBox="0 0 32 32" fill="none" aria-hidden="true">
  <path d="M16 9.4c-5.2 0-9.4 3.6-9.4 8.4 0 5 3.9 8.7 9.4 8.7s9.4-3.7 9.4-8.7c0-4.8-4.2-8.4-9.4-8.4Z" fill="currentColor"/>
  <path d="M16 9.2c.3-2 1.6-3.5 3.7-4.1M16 9.2c-.3-2-1.6-3.5-3.7-4.1" stroke="var(--leaf)" stroke-width="2" stroke-linecap="round"/>
  <path d="M16 9.2v-4.4" stroke="var(--leaf)" stroke-width="2" stroke-linecap="round"/>
  <path d="M11.6 6.8c2.2.7 3.4 1.5 4.4 2.4 1-1 2.2-1.7 4.4-2.4" stroke="var(--leaf)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
</svg>`;

// ------------------------------------------------------------------ coquille

const NAV = [
  { id: 'feed', label: 'Accueil', icon: ICON.home, route: '#/feed' },
  { id: 'messages', label: 'Messages', icon: ICON.messages, route: '#/messages' },
  { id: 'network', label: 'Réseau', icon: ICON.network, route: '#/network' },
  { id: 'communities', label: 'Groupes', icon: ICON.communities, route: '#/communities' },
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
    <div class="brand">${LOGO}<span class="brand-word">TOMATE</span></div>
    <div class="row" style="gap:6px">
      ${statusChip()}
      ${S.profile ? `<button class="icon-btn notif-btn" data-act="nav" data-route="#/notifications" aria-label="Notifications">${ICON.bell}${S.unread > 0 ? `<span class="notif-badge">${S.unread > 9 ? '9+' : S.unread}</span>` : ''}</button>` : ''}
      <button class="icon-btn" data-act="nav" data-route="#/search" aria-label="Rechercher">${ICON.search}</button>
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
    ${S.lastError ? notice(S.lastError, 'error', true) : ''}
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
      <p class="hint">Aucune adresse e-mail demandée pour commencer. Vous pourrez protéger le compte ensuite dans les paramètres, pour le retrouver sur un autre appareil.</p>
    </form>
    <details class="signin">
      <summary>J'ai déjà un compte</summary>
      <form data-form="signin" class="col" style="margin-top:10px">
        <div class="field">
          <label for="signin-email">E-mail du compte</label>
          <input class="input" id="signin-email" name="email" type="email" required placeholder="vous@exemple.fr" autocomplete="email" />
        </div>
        <div class="field">
          <label for="signin-password">Mot de passe</label>
          <input class="input" id="signin-password" name="password" type="password" required placeholder="Votre mot de passe" autocomplete="current-password" />
        </div>
        <button class="btn btn-soft btn-block" type="submit">Se connecter</button>
      </form>
    </details>
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
    ${storiesBar()}
    <div class="card">
      <form data-form="publish" class="compose">
        <label for="compose" class="composer-target">${ICON.people} Tout le monde</label>
        <textarea id="compose" name="body" maxlength="${MAX_POST_LENGTH}" placeholder="Quoi de neuf ?" aria-label="Nouvelle publication">${esc(readDraft(null))}</textarea>
        ${composerMediaPreview()}
        <div class="row-between">
          <div class="composer-tools">
            <label class="tool-btn" for="post-file">${ICON.image} Photo / vidéo<input id="post-file" type="file" accept="image/*,video/*" /></label>
            <button class="tool-btn" type="button" data-act="event-new">${ICON.chart} Événement</button>
            <span class="tiny" data-counter="publish">0/${MAX_POST_LENGTH}</span>
          </div>
          <button class="btn btn-primary" type="submit">Publier</button>
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
  const event = decodeEvent(post.body);
  const mine = post.author_id === S.profile?.id;
  // Before 0006 the feed has no community_name: fall back to the cached group list.
  const communityName = post.community_id
    ? post.community_name ?? S.communities.find((c) => c.id === post.community_id)?.name ?? 'Groupe'
    : null;
  return `<article class="post">
    <span class="avatar">${initials(post.author_display_name || post.author_handle)}</span>
    <div class="grow">
      <div class="post-head">
        <strong>${esc(post.author_display_name || post.author_handle || 'Anonyme')}</strong>
        <span class="post-meta">@${esc(post.author_handle ?? '')} · ${fmtDate(post.created_at)}</span>
        ${post.community_id && communityName
          ? `<button class="chip-community" data-act="open-community" data-id="${esc(post.community_id)}">${esc(communityName)}</button>`
          : ''}
        ${post.pending ? '<span class="pending-label">En attente d\'envoi</span>' : ''}
        ${post.failed ? '<span class="pending-label" style="color:var(--danger)">Non envoyé</span>' : ''}
      </div>
      ${event ? eventCard(post, event) : post.body ? `<div class="post-body" data-body-post="${esc(post.id)}"></div>` : ''}
      ${postMediaHtml(post)}
      <div class="post-actions">
        ${event ? '' : `<button class="action ${post.reacted_by_me ? 'liked' : ''}" data-act="like" data-id="${esc(post.id)}" aria-pressed="${!!post.reacted_by_me}">${ICON.heart}<span>${post.reaction_count ?? 0}</span></button>`}
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
    await loadRsvps(merged);
    S.lastError = null;
  } catch (error) {
    S.lastError = humanError(error);
  }
}

// --------------------------------------------------------------------- stories

const STORY_TTL_MS = 24 * 60 * 60 * 1000;

const storyAlive = (story) => new Date(story?.expires_at ?? 0).getTime() > Date.now();

const storyRemaining = (story) => {
  const left = Math.max(0, new Date(story.expires_at).getTime() - Date.now());
  const minutes = Math.round(left / 60000);
  if (minutes >= 60) return `expire dans ${Math.round(minutes / 60)} h`;
  if (minutes >= 1) return `expire dans ${minutes} min`;
  return 'expire bientôt';
};

function storyBubble(story) {
  const mine = story.author_id === S.profile?.id;
  const label = mine ? 'Vous' : story.author_handle || 'Anonyme';
  const name = story.author_display_name || story.author_handle || 'Anonyme';
  return `<button class="story-bubble" data-act="story-open" data-id="${esc(story.id)}" aria-label="Story de ${esc(name)}">
    <span class="story-ring ${story.viewed_by_me ? 'seen' : ''}">
      <span class="story-face">${esc(initials(name))}</span>
    </span>
    <span class="story-name">${esc(label)}</span>
    ${story.pending ? '<span class="story-flag">en attente</span>' : ''}
    ${story.failed ? '<span class="story-flag ko">non envoyée</span>' : ''}
  </button>`;
}

function storiesBar() {
  const alive = S.stories.filter(storyAlive);
  // One bubble per author, like every story bar: the viewer shows that author's latest story.
  const latest = new Map();
  alive.forEach((story) => {
    const current = latest.get(story.author_id);
    if (!current || new Date(story.created_at) > new Date(current.created_at)) latest.set(story.author_id, story);
  });
  const ordered = [...latest.values()].sort((a, b) => {
    const mine = Number(b.author_id === S.profile?.id) - Number(a.author_id === S.profile?.id);
    return mine || new Date(b.created_at) - new Date(a.created_at);
  });
  return `<div class="stories" aria-label="Stories">
    <button class="story-bubble" data-act="story-new" aria-label="Créer une story">
      <span class="story-ring add"><span class="story-face">+</span></span>
      <span class="story-name">Votre story</span>
    </button>
    ${ordered.map(storyBubble).join('')}
  </div>`;
}

async function loadStories({ refresh = false } = {}) {
  const cached = await cacheGet('stories');
  if (Array.isArray(cached)) S.stories = cached.filter(storyAlive);
  if (!refresh || !ONLINE()) return;
  try {
    await ensureRemoteSession();
    const rows = await rest('stories_feed', { query: 'select=*&order=created_at.asc&limit=120' });
    const seen = new Map(S.stories.map((s) => [s.id, s.viewed_by_me === true]));
    const pending = S.stories.filter((s) => s.pending || s.failed);
    const merged = rows
      .map((row) => ({ ...row, viewed_by_me: row.viewed_by_me === true || seen.get(row.id) === true }))
      .filter(storyAlive);
    const missing = pending.filter((p) => !merged.some((m) => m.id === p.id));
    S.stories = [...merged, ...missing].filter(storyAlive);
    await cacheSet('stories', S.stories);
    S.lastError = null;
  } catch (error) {
    S.lastError = humanError(error);
  }
}

const STORY_BACKGROUNDS = ['sunset', 'ocean', 'forest', 'night', 'plain'];

const STORY_IMAGE_MAX_BYTES = 12 * 1024 * 1024;
const STORY_VIDEO_MAX_BYTES = 25 * 1024 * 1024;

function storyMediaUrl(path) {
  const encoded = String(path ?? '').split('/').map(encodeURIComponent).join('/');
  return `${SUPABASE_URL}/storage/v1/object/public/stories/${encoded}`;
}

async function compressStoryImage(file) {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 1440 / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(bitmap.width * scale));
  canvas.height = Math.max(1, Math.round(bitmap.height * scale));
  canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close?.();
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('Compression de la photo impossible.'))),
      'image/jpeg',
      0.82,
    );
  });
}

/** Phrase a picked file into something the story bucket accepts, or explain why not. */
async function prepareStoryMedia(file) {
  if (!file) return null;
  if (file.type.startsWith('image/')) {
    if (file.size > STORY_IMAGE_MAX_BYTES) throw new Error('Photo trop lourde : 12 Mo maximum.');
    const blob = await compressStoryImage(file);
    return { blob, type: 'image', mime: 'image/jpeg', ext: 'jpg', preview: URL.createObjectURL(blob) };
  }
  if (file.type.startsWith('video/')) {
    if (file.size > STORY_VIDEO_MAX_BYTES) throw new Error('Vidéo trop lourde : 25 Mo maximum.');
    const ext = file.type.includes('webm') ? 'webm' : file.type.includes('quicktime') ? 'mov' : 'mp4';
    return { blob: file, type: 'video', mime: file.type, ext, preview: URL.createObjectURL(file) };
  }
  throw new Error('Format non pris en charge : photo ou vidéo uniquement.');
}

async function uploadStoryMedia(path, media) {
  const session = await ensureRemoteSession();
  if (!session) throw new Error('Connexion requise pour envoyer une photo ou une vidéo.');
  const response = await fetch(`${SUPABASE_URL}/storage/v1/object/stories/${path}`, {
    method: 'POST',
    headers: {
      apikey: SUPABASE_KEY,
      Authorization: `Bearer ${session.accessToken}`,
      'Content-Type': media.mime,
      'x-upsert': 'false',
    },
    body: media.blob,
  });
  if (!response.ok) {
    // 400/404 here almost always means the `stories` bucket is missing (migration 0005).
    const hint = response.status === 400 || response.status === 404
      ? 'Stockage photo/vidéo non activé : appliquez la migration 0005_media_reseau.sql.'
      : `Envoi du média refusé (HTTP ${response.status}).`;
    throw new Error(hint);
  }
}

function showStoryViewer(story) {
  const dialog = document.createElement('dialog');
  dialog.className = 'story-dialog';
  const mine = story.author_id === S.profile?.id;
  dialog.innerHTML = `
    <div class="story-canvas bg-${STORY_BACKGROUNDS.includes(story.background) ? story.background : 'sunset'}">
      <div class="story-top">
        <div class="grow">
          <strong>${esc(story.author_display_name || story.author_handle || 'Anonyme')}</strong>
          <div class="tiny" style="opacity:.8">@${esc(story.author_handle ?? '')} · ${esc(storyRemaining(story))}</div>
        </div>
        ${mine ? `<button class="icon-btn" data-act="story-delete" data-id="${esc(story.id)}" aria-label="Supprimer">${ICON.trash}</button>` : ''}
        <button class="icon-btn" data-act="story-close" aria-label="Fermer">${ICON.close}</button>
      </div>
      ${story.media_path
        ? story.media_type === 'video'
          ? `<video class="story-media" src="${esc(storyMediaUrl(story.media_path))}" controls autoplay playsinline loop></video>`
          : `<img class="story-media" src="${esc(storyMediaUrl(story.media_path))}" alt="Story de ${esc(story.author_handle ?? '')}">`
        : ''}
      <div class="story-text" data-story-body="${esc(story.id)}"></div>
      <div class="story-foot">Disparaît automatiquement 24 h après sa publication.</div>
    </div>`;
  document.body.appendChild(dialog);
  dialog.querySelector('[data-story-body]').textContent = story.body;
  dialog.showModal();
  const close = () => {
    if (dialog.open) dialog.close();
    dialog.remove();
  };
  dialog.addEventListener('click', (event) => {
    if (event.target.closest('[data-act="story-close"]') || event.target === dialog) close();
  });
  dialog.addEventListener('cancel', () => dialog.remove());
  return { close, dialog };
}

async function openStory(id) {
  const story = S.stories.find((s) => s.id === id && storyAlive(s));
  if (!story) return toast('Cette story a expiré.');
  showStoryViewer(story);
  if (story.viewed_by_me) return;
  story.viewed_by_me = true;
  await cacheSet('stories', S.stories);
  await enqueue('story_view', { story_id: story.id, viewer_id: S.profile.id });
  if (ONLINE()) await syncNow({ silent: true });
}

function storyComposer() {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.innerHTML = `
      <h2>Nouvelle story</h2>
      <p class="muted small">Visible 24 h par les personnes autorisées à voir votre profil, puis supprimée de l'affichage — même hors ligne.</p>
      <div class="story-preview bg-sunset" id="story-preview"><span>Votre texte…</span></div>
      <div class="field" style="margin-top:10px">
        <label for="story-text">Texte de la story</label>
        <textarea class="textarea" id="story-text" maxlength="${MAX_STORY_LENGTH}" placeholder="Ce que vous voulez partager aujourd'hui…"></textarea>
      </div>
      <div class="field" style="margin-top:10px">
        <label for="story-file">Photo ou vidéo (facultatif)</label>
        <input class="input" id="story-file" type="file" accept="image/*,video/*">
        <div class="tiny muted" id="story-file-info">Photo jusqu'à 12 Mo, vidéo jusqu'à 25 Mo. Envoi avec connexion.</div>
      </div>
      <div class="story-palette" role="group" aria-label="Fond de la story">
        ${STORY_BACKGROUNDS.map((bg) => `<button type="button" class="story-swatch bg-${bg} ${bg === 'sunset' ? 'active' : ''}" data-bg="${bg}" aria-label="Fond ${bg}" aria-pressed="${bg === 'sunset'}"></button>`).join('')}
      </div>
      <div class="row">
        <button class="btn btn-soft" data-choice="cancel">Annuler</button>
        <button class="btn btn-primary" data-choice="ok">Publier</button>
      </div>`;
    document.body.appendChild(dialog);
    dialog.showModal();
    const text = dialog.querySelector('#story-text');
    const preview = dialog.querySelector('#story-preview');
    const fileInput = dialog.querySelector('#story-file');
    const fileInfo = dialog.querySelector('#story-file-info');
    text.focus();
    let background = 'sunset';
    let media = null;
    fileInput.addEventListener('change', async () => {
      const file = fileInput.files?.[0];
      if (!file) return;
      fileInput.disabled = true;
      fileInfo.textContent = 'Préparation du média…';
      try {
        media = await prepareStoryMedia(file);
        preview.innerHTML = media.type === 'video'
          ? `<video class="story-media" src="${media.preview}" muted playsinline autoplay loop></video>`
          : `<img class="story-media" src="${media.preview}" alt="Aperçu de la story">`;
        preview.classList.remove('placeholder');
        fileInfo.textContent = media.type === 'video' ? 'Vidéo prête à envoyer.' : 'Photo compressée, prête à envoyer.';
      } catch (error) {
        media = null;
        fileInput.value = '';
        fileInfo.textContent = error.message;
      } finally {
        fileInput.disabled = false;
      }
    });
    text.addEventListener('input', () => {
      preview.textContent = text.value.trim() || 'Votre texte…';
      preview.classList.toggle('placeholder', !text.value.trim());
    });
    dialog.addEventListener('click', (event) => {
      const swatch = event.target.closest('[data-bg]');
      if (swatch) {
        background = swatch.dataset.bg;
        preview.className = `story-preview bg-${background}${text.value.trim() ? '' : ' placeholder'}`;
        dialog.querySelectorAll('[data-bg]').forEach((node) => {
          const active = node.dataset.bg === background;
          node.classList.toggle('active', active);
          node.setAttribute('aria-pressed', String(active));
        });
        return;
      }
      const choice = event.target.closest('[data-choice]')?.dataset.choice;
      if (!choice) return;
      const value = text.value.trim();
      if (choice === 'ok' && !value && !media) {
        toast('Écrivez un texte ou choisissez une photo.');
        return;
      }
      dialog.close();
      dialog.remove();
      if (media?.preview && choice !== 'ok') URL.revokeObjectURL(media.preview);
      resolve(choice === 'ok' ? { text: value, background, media } : null);
    });
    dialog.addEventListener('cancel', () => {
      dialog.remove();
      resolve(null);
    });
  });
}

async function publishStory(draft = {}) {
  const body = String(draft.text ?? '').trim().slice(0, MAX_STORY_LENGTH);
  const media = draft.media ?? null;
  if (!body && !media) return;
  const story = {
    id: crypto.randomUUID(),
    author_id: S.profile.id,
    author_handle: S.profile.handle,
    author_display_name: S.profile.display_name,
    body,
    background: STORY_BACKGROUNDS.includes(draft.background) ? draft.background : 'sunset',
    created_at: new Date().toISOString(),
    expires_at: new Date(Date.now() + STORY_TTL_MS).toISOString(),
    viewed_by_me: true,
    pending: true,
    media_path: null,
    media_type: null,
  };
  if (media) {
    // A media story is uploaded first: publishing a row that points to a missing file would
    // advertise a story nobody can open. Offline, the honest answer is "connexion requise".
    if (!ONLINE() || !(await ensureRemoteSession())) {
      toast('Photo et vidéo : connexion requise. Le texte, lui, part même hors ligne.');
      return;
    }
    const path = `${S.profile.id}/${story.id}.${media.ext}`;
    toast('Envoi du média…');
    try {
      await uploadStoryMedia(path, media);
    } catch (error) {
      S.lastError = error.message;
      render();
      return;
    }
    story.media_path = path;
    story.media_type = media.type;
  }
  S.stories = [story, ...S.stories];
  await cacheSet('stories', S.stories);
  // Send the media columns only when the story has media: a text story must keep working
  // before migration 0005 adds those columns.
  const payload = {
    id: story.id,
    author_id: story.author_id,
    body: story.body,
    background: story.background,
  };
  if (story.media_path) {
    payload.media_path = story.media_path;
    payload.media_type = story.media_type;
  }
  await enqueue('story_create', payload);
  render();
  await syncNow();
}

async function deleteStory(id) {
  const ok = await confirmDialog({
    title: 'Supprimer cette story ?',
    text: 'Elle disparaîtra immédiatement, pour vous comme pour les autres.',
    confirmLabel: 'Supprimer',
    danger: true,
  });
  if (!ok) return false;
  S.stories = S.stories.filter((s) => s.id !== id);
  await cacheSet('stories', S.stories);
  await enqueue('story_delete', { id });
  render();
  await syncNow({ silent: true });
  return true;
}

// ------------------------------------------------- brouillons et médias

// Un brouillon survit à la fermeture de l'onglet : « publication préparée » ne doit pas
// être une promesse vide. Clé distincte par communauté.
const draftKey = (communityId) => (communityId ? `tomate:draft:community:${communityId}` : 'tomate:draft:feed');

function readDraft(communityId) {
  try {
    return localStorage.getItem(draftKey(communityId)) ?? '';
  } catch {
    return '';
  }
}

function writeDraft(communityId, value) {
  try {
    if (String(value ?? '').trim()) localStorage.setItem(draftKey(communityId), value);
    else localStorage.removeItem(draftKey(communityId));
  } catch {
    // Private mode can refuse storage: the draft is simply not kept.
  }
}

const postMediaUrl = (path) =>
  `${SUPABASE_URL}/storage/v1/object/public/posts/${String(path ?? '')
    .split('/')
    .map(encodeURIComponent)
    .join('/')}`;

// Mêmes règles que les stories : photo compressée à 1440 px, vidéo jusqu'à 25 Mo.
const preparePostMedia = prepareStoryMedia;

function composerMediaPreview() {
  const media = S.postMedia;
  if (!media) return '';
  const preview = media.type === 'video'
    ? `<video src="${esc(media.preview)}" muted playsinline preload="metadata"></video>`
    : `<img src="${esc(media.preview)}" alt="Aperçu de la photo" />`;
  return `<div class="media-strip" id="composer-media">${preview}
    <div class="grow">${media.type === 'video' ? 'Vidéo prête à envoyer' : 'Photo compressée, prête à envoyer'}
      <div class="tiny muted">${Math.max(1, Math.round(media.blob.size / 1024))} Ko · ${media.ext.toUpperCase()}</div>
    </div>
    <button class="remove" type="button" data-act="drop-media" aria-label="Retirer le média">${ICON.close}</button>
  </div>`;
}

function postMediaHtml(post) {
  if (!post.media_path) return '';
  const url = postMediaUrl(post.media_path);
  if (post.media_type === 'video') {
    return `<video class="post-media video" src="${esc(url)}" controls playsinline preload="metadata"></video>`;
  }
  return `<img class="post-media" src="${esc(url)}" alt="Photo publiée par ${esc(post.author_display_name || post.author_handle || 'un membre')}" loading="lazy" />`;
}

async function uploadPostMedia(path, media) {
  const session = await ensureRemoteSession();
  if (!session) throw new Error('Connexion requise pour envoyer une photo ou une vidéo.');
  const response = await fetch(`${SUPABASE_URL}/storage/v1/object/posts/${path}`, {
    method: 'POST',
    headers: {
      apikey: SUPABASE_KEY,
      Authorization: `Bearer ${session.accessToken}`,
      'Content-Type': media.mime,
      'x-upsert': 'false',
    },
    body: media.blob,
  });
  if (!response.ok) {
    const hint = response.status === 400 || response.status === 404
      ? 'Stockage photo/vidéo non activé : appliquez la migration 0006_social.sql.'
      : `Envoi du média refusé (HTTP ${response.status}).`;
    throw new Error(hint);
  }
  return path;
}

async function attachPostMedia(file) {
  const media = await preparePostMedia(file);
  if (!media) return;
  S.postMedia = media;
  render();
}

async function publishPost(body, { communityId = null, media = S.postMedia } = {}) {
  const text = body.trim();
  if (!text && !media) return toast("Écrivez un message ou ajoutez une photo.");
  if (text.length > MAX_POST_LENGTH) return toast('Message trop long.');

  const id = crypto.randomUUID();
  let mediaPath = null;
  let mediaType = null;
  if (media) {
    if (!ONLINE()) return toast('Photo ou vidéo : connexion requise. Le texte, lui, part hors ligne.');
    mediaPath = `${S.profile.id}/${id}.${media.ext}`;
    mediaType = media.type;
    await uploadPostMedia(mediaPath, media);
  }

  const community = communityId ? S.communities.find((c) => c.id === communityId) : null;
  const post = {
    id,
    author_id: S.profile.id,
    author_handle: S.profile.handle,
    author_display_name: S.profile.display_name,
    body: text,
    community_id: communityId,
    community_name: community?.name ?? null,
    visibility: communityId ? 'Community' : 'Public',
    created_at: new Date().toISOString(),
    reaction_count: 0,
    comment_count: 0,
    reacted_by_me: false,
    media_path: mediaPath,
    media_type: mediaType,
    pending: true,
  };

  const payload = {
    id: post.id,
    author_id: post.author_id,
    body: text,
    visibility: post.visibility,
    community_id: communityId,
  };
  // The media columns only exist after 0006: a text post must keep working before it.
  if (mediaPath) {
    payload.media_path = mediaPath;
    payload.media_type = mediaType;
  }

  S.feed = [post, ...S.feed];
  await cacheSet('feed', S.feed);
  await enqueue('post_insert', payload);
  writeDraft(communityId, '');
  S.draft = '';
  S.postMedia = null;
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
          ${decodeEvent(post.body) ? eventCard(post, decodeEvent(post.body)) : `<div class="post-body" data-body-post="${esc(post.id)}"></div>`}
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
        ${comment.failed ? '<span class="pending-label" style="color:var(--danger)">Non envoyé</span>' : ''}
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
      <div class="row" style="gap:8px">
        <button class="btn btn-soft" data-act="qr-add">${ICON.qr} QR</button>
        <button class="btn btn-soft" data-act="new-conversation">${ICON.plus} Nouvelle</button>
      </div>
    </div>
    <div class="card" style="padding:4px 16px">
      ${S.conversations.length === 0
        ? emptyState('✉️', 'Aucune conversation.', 'Démarrez une discussion avec un pseudo TOMATE.')
        : S.conversations.map(conversationItem).join('')}
    </div>
    ${notice("Les messages restent sur l'appareil et partent dès que le réseau le permet. La flamme 🔥 compte les jours consécutifs où vous avez écrit tous les deux. Le chiffrement de bout en bout n'est pas encore implémenté : l'interface ne le prétend pas.", 'info')}
  `, { actions: `<button class="icon-btn" data-act="refresh-messages" aria-label="Actualiser">${ICON.refresh}</button>` });
}

function conversationItem(conversation) {
  return `<button class="list-row" data-act="open-chat" data-id="${esc(conversation.id)}">
    <span class="avatar">${initials(conversation.title || 'Conversation')}</span>
    <span class="grow">
      <span class="title">${esc(conversation.title || (conversation.is_group ? 'Groupe' : 'Conversation'))}
        ${S.streaks[conversation.id] ? `<span class="streak" title="Jours consécutifs à écrire tous les deux">🔥 ${S.streaks[conversation.id]}</span>` : ''}</span>
      <span class="sub">${fmtDate(conversation.last_message_at)}</span>
    </span>
  </button>`;
}

function chatView() {
  const conversation = S.conversations.find((c) => c.id === S.param);
  return shell(`
    ${S.lastError ? notice(S.lastError, 'error', true) : ''}
    <div class="card" style="min-height:40vh;display:flex;flex-direction:column;gap:8px;padding:14px">
      ${visibleMessages().length === 0 ? emptyState('💬', 'Aucun message.', 'Écrivez le premier message.') : visibleMessages().map(messageItem).join('')}
    </div>
    ${chatMediaPreview()}
    <form data-form="send-message" data-conversation="${esc(S.param)}" class="card" style="margin-top:12px;display:flex;gap:10px;align-items:flex-end">
      <input type="file" id="chat-media-input" accept="image/*,video/*,audio/*" hidden />
      <button class="icon-btn" type="button" data-act="poll-new" style="flex:none" aria-label="Créer un sondage">${ICON.chart}</button>
      <button class="icon-btn" type="button" data-act="chat-attach" style="flex:none" aria-label="Joindre une photo, une vidéo ou un message vocal">${ICON.paperclip}</button>
      <textarea class="textarea" name="body" maxlength="${MAX_MESSAGE_LENGTH}" placeholder="Message…" aria-label="Message" style="min-height:52px"></textarea>
      <button class="icon-btn" type="submit" style="color:var(--primary);flex:none" aria-label="Envoyer">${ICON.send}</button>
    </form>
    <div class="chat-actions">
      <button class="btn btn-soft btn-sm" data-act="leave-chat">Quitter la conversation</button>
      ${conversation?.created_by === S.profile?.id
        ? '<button class="btn btn-soft btn-sm danger-text" data-act="delete-chat">Supprimer pour tout le monde</button>'
        : ''}
    </div>
  `, {
    title: `${conversation?.title || 'Conversation'}${S.streaks[S.param] ? ` · 🔥 ${S.streaks[S.param]}` : ''}`,
    back: '#/messages',
    actions: `${conversation ? `<button class="icon-btn" data-act="call-audio" aria-label="Appeler en audio">${ICON.phone}</button><button class="icon-btn" data-act="call-video" aria-label="Appeler en vidéo">${ICON.camera}</button>` : ''}`,
  });
}

// ------------------------------------------------- pièces jointes de conversation
const CHAT_MEDIA_MAX_BYTES = 25 * 1024 * 1024;
const CHAT_MEDIA_TTL_SECONDS = 6 * 3600;

async function prepareChatMedia(file) {
  if (!file) return null;
  if (file.type.startsWith('audio/')) {
    if (file.size > 10 * 1024 * 1024) throw new Error('Message vocal trop lourd : 10 Mo maximum.');
    const ext = file.type.includes('mpeg') ? 'mp3'
      : file.type.includes('mp4') ? 'm4a'
        : file.type.includes('ogg') ? 'ogg'
          : 'webm';
    return { blob: file, type: 'audio', mime: file.type, ext, preview: null };
  }
  const media = await prepareStoryMedia(file);
  if (media.blob.size > CHAT_MEDIA_MAX_BYTES) throw new Error('Pièce jointe trop lourde : 25 Mo maximum.');
  return media;
}

async function uploadChatMedia(conversationId, media) {
  const session = await ensureRemoteSession();
  if (!session) throw new Error('Connexion requise pour envoyer une pièce jointe.');
  const path = `${conversationId}/${session.userId}/${crypto.randomUUID()}.${media.ext}`;
  const encoded = path.split('/').map(encodeURIComponent).join('/');
  const response = await fetch(`${SUPABASE_URL}/storage/v1/object/messages/${encoded}`, {
    method: 'POST',
    headers: {
      apikey: SUPABASE_KEY,
      Authorization: `Bearer ${session.accessToken}`,
      'Content-Type': media.mime,
      'x-upsert': 'false',
    },
    body: media.blob,
  });
  if (!response.ok) {
    const hint = response.status === 400 || response.status === 404
      ? 'Pièces jointes non activées : appliquez la migration 0009_medias_messagerie.sql.'
      : `Envoi de la pièce jointe refusé (HTTP ${response.status}).`;
    throw new Error(hint);
  }
  return { path, mime: media.mime, size: media.blob.size, kind: media.type };
}

/** URL signée temporaire : le bucket messages est privé, jamais servi en public. */
async function chatMediaUrl(path) {
  if (!path) return null;
  const cached = S.mediaUrls[path];
  if (cached && cached.expires > Date.now()) return cached.url;
  const session = await ensureRemoteSession();
  if (!session) return null;
  const encoded = path.split('/').map(encodeURIComponent).join('/');
  const response = await fetch(`${SUPABASE_URL}/storage/v1/object/sign/messages/${encoded}`, {
    method: 'POST',
    headers: {
      apikey: SUPABASE_KEY,
      Authorization: `Bearer ${session.accessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ expiresIn: CHAT_MEDIA_TTL_SECONDS }),
  });
  if (!response.ok) return null;
  const payload = await response.json();
  if (!payload?.signedURL) return null;
  const url = `${SUPABASE_URL}/storage/v1${payload.signedURL}`;
  S.mediaUrls[path] = { url, expires: Date.now() + (CHAT_MEDIA_TTL_SECONDS - 600) * 1000 };
  return url;
}

async function resolveChatMedia(messages) {
  const paths = [...new Set((messages ?? []).map((m) => m.media_path).filter(Boolean))]
    .filter((path) => !S.mediaUrls[path] || S.mediaUrls[path].expires <= Date.now());
  for (const path of paths) {
    try {
      await chatMediaUrl(path);
    } catch {
      // L'affichage dira que la pièce jointe est momentanément indisponible.
    }
  }
}

function messageMediaHtml(message) {
  if (!message.media_path) return '';
  const url = S.mediaUrls[message.media_path]?.url ?? null;
  if (!url) {
    return '<div class="chat-media unavailable tiny">Pièce jointe indisponible pour le moment.</div>';
  }
  if (message.media_kind === 'video') {
    return `<video class="chat-media" src="${esc(url)}" controls playsinline preload="metadata"></video>`;
  }
  if (message.media_kind === 'audio') {
    return `<audio class="chat-media audio" src="${esc(url)}" controls preload="metadata"></audio>`;
  }
  return `<a href="${esc(url)}" target="_blank" rel="noopener"><img class="chat-media" src="${esc(url)}" alt="Photo envoyée" loading="lazy" /></a>`;
}

function chatMediaPreview() {
  const media = S.chatMedia;
  if (!media) return '';
  const preview = media.type === 'video'
    ? `<video src="${esc(media.preview)}" muted playsinline preload="metadata"></video>`
    : media.type === 'image'
      ? `<img src="${esc(media.preview)}" alt="Aperçu de la photo" />`
      : '<span class="chat-media-glyph">🎙️</span>';
  const label = media.type === 'audio' ? 'Message vocal' : media.type === 'video' ? 'Vidéo' : 'Photo';
  return `<div class="chat-media-preview">${preview}
    <div class="grow">${label}
      <div class="tiny muted">${Math.max(1, Math.round(media.blob.size / 1024))} Ko · ${esc(media.ext.toUpperCase())}</div>
    </div>
    <button class="remove" type="button" data-act="chat-drop-media" aria-label="Retirer la pièce jointe">${ICON.close}</button>
  </div>`;
}

function messageItem(message) {
  const poll = decodePoll(message.body);
  if (poll) return pollBubble(message, poll);
  const mine = message.sender_id === S.profile?.id;
  return `<div style="align-self:${mine ? 'flex-end' : 'flex-start'};max-width:85%;background:${mine ? 'var(--primary)' : 'var(--surface-2)'};color:${mine ? 'var(--on-primary)' : 'var(--text)'};padding:9px 13px;border-radius:16px">
    ${messageMediaHtml(message)}
    ${message.body ? `<div style="white-space:pre-wrap">${esc(message.body)}</div>` : ''}
    <div class="tiny" style="opacity:.75;margin-top:3px">${fmtDate(message.created_at)}${message.pending ? ' · en attente' : message.failed ? ' · non envoyé' : ''}</div>
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
      const conversations = await rest('conversations', {
        query: `select=*&id=in.(${ids.join(',')})&order=last_message_at.desc&limit=50`,
      });
      // The peer of a direct conversation is not a column: it is the other member. Cached
      // so the QR flow can recognise an existing conversation without asking the server.
      let peerByConversation = new Map();
      try {
        const members = await rest('conversation_members', {
          query: `select=conversation_id,user_id&conversation_id=in.(${ids.join(',')})`,
        });
        peerByConversation = new Map(
          members
            .filter((m) => m.user_id !== session.userId)
            .map((m) => [m.conversation_id, m.user_id]),
        );
      } catch {
        // Peer ids are a convenience; the list still works without them.
      }
      S.conversations = conversations.map((conversation) => ({
        ...conversation,
        peer_id: conversation.peer_id ?? peerByConversation.get(conversation.id) ?? null,
      }));
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
    // Keep local-only messages, including refused ones: the user's text must not vanish silently.
    const local = S.messages.filter((m) => m.pending || m.failed);
    S.messages = [...rows, ...local.filter((m) => !rows.some((r) => r.id === m.id))];
    await cacheSet(key, S.messages);
    await resolveChatMedia(S.messages);
  } catch {
    // Cached messages stay readable.
  }
}

async function sendMessage(conversationId, body, media = S.chatMedia) {
  const text = body.trim();
  if (!text && !media) return;
  if (text.length > MAX_MESSAGE_LENGTH) return toast('Message trop long.');

  // La pièce jointe part avant le message : un message ne doit jamais pointer vers un
  // fichier qui n'est pas arrivé. En cas d'échec, texte et aperçu restent en place.
  let attachment = null;
  if (media) {
    if (!ONLINE()) return toast('Pièce jointe : connexion requise. Le texte, lui, part hors ligne.');
    try {
      attachment = await uploadChatMedia(conversationId, media);
    } catch (error) {
      toast(humanError(error));
      return;
    }
  }

  const message = {
    id: crypto.randomUUID(),
    client_id: crypto.randomUUID(),
    conversation_id: conversationId,
    sender_id: S.profile.id,
    body: text,
    media_path: attachment?.path ?? null,
    media_kind: attachment?.kind ?? null,
    media_mime: attachment?.mime ?? null,
    media_size: attachment?.size ?? null,
    created_at: new Date().toISOString(),
    pending: true,
  };
  S.messages = [...S.messages, message];
  await cacheSet(`messages:${conversationId}`, S.messages);
  // Only server columns go on the wire: local state (`pending`) is not a database column.
  await enqueue('message_send', {
    id: message.id,
    client_id: message.client_id,
    conversation_id: conversationId,
    sender_id: message.sender_id,
    body: text,
    encrypted: false,
    ...(attachment
      ? {
        media_path: attachment.path,
        media_kind: attachment.kind,
        media_mime: attachment.mime,
        media_size: attachment.size,
      }
      : {}),
  });
  if (S.chatMedia?.preview) URL.revokeObjectURL(S.chatMedia.preview);
  S.chatMedia = null;
  await resolveChatMedia([message]);
  render();
  await syncNow();
}

async function newConversation() {
  const people = await contactPickerDialog({ title: 'Nouvelle conversation' });
  if (!people || people.length === 0) return;
  const isGroup = people.length > 1;
  const fallbackTitle = people.map((p) => p.display_name || p.handle).slice(0, 3).join(', ');
  let title = people[0].display_name || people[0].handle;
  if (isGroup) {
    const asked = await promptDialog({
      title: 'Nom du groupe',
      label: 'Nom du groupe',
      placeholder: fallbackTitle,
    });
    title = (asked ?? '').trim() || fallbackTitle;
  }
  try {
    const id = crypto.randomUUID();
    const conversation = {
      id,
      title,
      is_group: isGroup,
      created_by: S.profile.id,
      created_at: new Date().toISOString(),
      last_message_at: new Date().toISOString(),
      pending: !ONLINE(),
    };
    S.conversations = [conversation, ...S.conversations];
    await cacheSet('conversations', S.conversations);
    await enqueue('conversation_create', { id, title, is_group: isGroup, created_by: S.profile.id });
    await enqueue('conversation_member_add', { conversation_id: id, user_id: S.profile.id });
    for (const person of people) {
      await enqueue('conversation_member_add', { conversation_id: id, user_id: person.id });
    }
    await syncNow();
    location.hash = `#/chat/${id}`;
    await navigate();
  } catch (error) {
    S.lastError = humanError(error);
    render();
  }
}

// ------------------------------------------------------------ ajout par QR code

const FRIEND_SCHEME = 'tomato://add';

function friendPayload() {
  const params = new URLSearchParams({
    u: S.profile.id,
    h: S.profile.handle ?? '',
    n: S.profile.display_name ?? '',
  });
  return `${FRIEND_SCHEME}?${params.toString()}`;
}

/** Accepts a tomato:// payload, a pasted link, or an https URL carrying the same query. */
function parseFriendPayload(raw) {
  const text = String(raw ?? '').trim();
  if (!text) return null;
  const normalised = text.replace(/^tomato:\/\//i, 'https://tomate.local/');
  let url;
  try {
    url = new URL(normalised);
  } catch {
    return null;
  }
  const id = url.searchParams.get('u');
  if (!id || !/^[A-Za-z0-9-]{3,64}$/.test(id)) return null;
  return {
    id,
    handle: (url.searchParams.get('h') ?? '').replace(/^@/, '').slice(0, 20),
    name: (url.searchParams.get('n') ?? '').slice(0, 40),
  };
}

async function addFriendFromText(raw) {
  const peer = parseFriendPayload(raw);
  if (!peer) return toast('Ce code ne contient pas une invitation TOMATE.');
  if (peer.id === S.profile.id) return toast("C'est votre propre code.");
  await createConversationWith(peer);
  toast(`Conversation avec @${peer.handle || peer.name || 'ce compte'} prête.`);
}

function qrDialog() {
  const dialog = document.createElement('dialog');
  dialog.className = 'qr-dialog';
  dialog.innerHTML = `
    <h2>Ajouter un ami</h2>
    <p class="muted small">Scannez le code de votre ami, ou faites scanner le vôtre. La conversation est créée même sans réseau : elle partira à la reconnexion.</p>
    <div class="qr-code" data-qr-code></div>
    <div class="tiny muted" style="text-align:center">@${esc(S.profile.handle ?? '')}</div>
    <div class="row" style="margin-top:10px">
      <button class="btn btn-soft" data-act="qr-copy">Copier le lien</button>
      <button class="btn btn-soft" data-act="qr-scan">Scanner</button>
    </div>
    <div class="field" style="margin-top:10px">
      <label for="qr-link">Lien ou code reçu</label>
      <input class="input" id="qr-link" placeholder="tomato://add?u=…&amp;h=…">
    </div>
    <video data-qr-video class="qr-video" playsinline muted hidden></video>
    <div class="tiny muted" data-qr-status></div>
    <div class="row">
      <button class="btn btn-soft" data-choice="cancel">Fermer</button>
      <button class="btn btn-primary" data-act="qr-open">Ouvrir</button>
    </div>`;
  document.body.appendChild(dialog);
  const code = dialog.querySelector('[data-qr-code]');
  const payload = friendPayload();
  try {
    const qr = qrcode(0, 'M');
    qr.addData(payload);
    qr.make();
    code.innerHTML = qr.createImgTag(5, 8);
  } catch {
    code.textContent = 'QR indisponible : utilisez le bouton « Copier le lien ».';
  }
  const stop = () => {
    dialog._stopScan?.();
    dialog._stream?.getTracks().forEach((track) => track.stop());
    dialog._stream = null;
  };
  dialog.addEventListener('close', stop);
  dialog.addEventListener('cancel', stop);
  dialog.addEventListener('click', (event) => {
    if (event.target.closest('[data-choice="cancel"]') || event.target === dialog) {
      dialog.close();
      dialog.remove();
    }
  });
  dialog.showModal();
}

async function scanFriendCode(dialog) {
  const status = dialog.querySelector('[data-qr-status]');
  const video = dialog.querySelector('[data-qr-video]');
  if (!('BarcodeDetector' in window)) {
    status.textContent = 'Scan caméra non pris en charge ici : collez le lien dans le champ.';
    return;
  }
  try {
    dialog._stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } } });
    video.srcObject = dialog._stream;
    video.hidden = false;
    await video.play();
  } catch (error) {
    status.textContent = `Caméra indisponible (${error.message}). Collez le lien à la place.`;
    return;
  }
  const detector = new BarcodeDetector({ formats: ['qr_code'] });
  status.textContent = 'Visez le QR code…';
  let stopped = false;
  dialog._stopScan = () => {
    stopped = true;
  };
  const tick = async () => {
    if (stopped || !video.isConnected) return;
    try {
      const codes = await detector.detect(video);
      const raw = codes?.[0]?.rawValue;
      if (raw) {
        dialog._stopScan?.();
        dialog._stream?.getTracks().forEach((track) => track.stop());
        dialog.close();
        dialog.remove();
        await addFriendFromText(raw);
        return;
      }
    } catch {
      // A frame can fail to decode (motion blur): keep scanning instead of aborting.
    }
    setTimeout(tick, 350);
  };
  tick();
}

async function createConversationWith(peer) {
  const existing = S.conversations.find((c) => c.peer_id === peer.id);
  if (existing) {
    location.hash = `#/chat/${existing.id}`;
    return;
  }
  const id = crypto.randomUUID();
  const now = new Date().toISOString();
  const conversation = {
    id,
    title: peer.name || peer.handle || 'Conversation',
    is_group: false,
    created_by: S.profile.id,
    created_at: now,
    last_message_at: now,
    peer_id: peer.id,
    pending: !ONLINE(),
  };
  S.conversations = [conversation, ...S.conversations];
  await cacheSet('conversations', S.conversations);
  await enqueue('conversation_create', { id, title: conversation.title, is_group: false, created_by: S.profile.id });
  await enqueue('conversation_member_add', { conversation_id: id, user_id: S.profile.id });
  if (!isLocalId(peer.id)) await enqueue('conversation_member_add', { conversation_id: id, user_id: peer.id });
  render();
  if (ONLINE()) await syncNow();
  location.hash = `#/chat/${id}`;
  await navigate();
}

// ------------------------------------------------------------- flammes 🔥
// Une flamme compte les jours consécutifs où les deux personnes ont écrit. Calculée à
// partir des messages réels : aucun compteur inventé, et elle reste juste hors ligne.

const dayKey = (value) => {
  const date = new Date(value);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
};

const shiftDay = (key, delta) => {
  const [year, month, day] = key.split('-').map(Number);
  return dayKey(new Date(year, month - 1, day + delta));
};

function computeStreak(days) {
  if (!days?.size) return 0;
  const today = dayKey(new Date());
  let cursor = (days.get(today)?.size ?? 0) >= 2 ? today : shiftDay(today, -1);
  let count = 0;
  while ((days.get(cursor)?.size ?? 0) >= 2) {
    count += 1;
    cursor = shiftDay(cursor, -1);
  }
  return count;
}

async function loadStreaks() {
  const cached = await cacheGet('streaks');
  if (cached && typeof cached === 'object') S.streaks = cached;
  if (!ONLINE() || S.conversations.length === 0) return;
  try {
    const ids = S.conversations.map((c) => c.id).slice(0, 50);
    const rows = await rest('messages', {
      query: `select=conversation_id,sender_id,created_at&conversation_id=in.(${ids.join(',')})&order=created_at.desc&limit=2000`,
    });
    const perConversation = new Map();
    rows.forEach((row) => {
      if (!perConversation.has(row.conversation_id)) perConversation.set(row.conversation_id, new Map());
      const days = perConversation.get(row.conversation_id);
      const key = dayKey(row.created_at);
      if (!days.has(key)) days.set(key, new Set());
      days.get(key).add(row.sender_id);
    });
    const streaks = {};
    ids.forEach((id) => {
      streaks[id] = computeStreak(perConversation.get(id));
    });
    S.streaks = streaks;
    await cacheSet('streaks', streaks);
  } catch {
    // Cached streaks stay displayed; a failed refresh must not erase them.
  }
}

// --------------------------------------------------------------- recherche

function searchResult(result) {
  const label = result.kind === 'person' ? 'Personne' : result.kind === 'community' ? 'Groupe' : 'Publication';
  const action = result.kind === 'person'
    ? `data-act="search-person" data-id="${esc(result.id)}" data-handle="${esc(result.handle ?? '')}" data-name="${esc(result.title ?? '')}"`
    : result.kind === 'community'
      ? `data-act="open-community" data-id="${esc(result.id)}"`
      : `data-act="open-post" data-id="${esc(result.id)}"`;
  return `<button class="search-result" ${action}>
    <span class="avatar" style="width:40px;height:40px;border-radius:14px;font-size:13px">${initials(result.title)}</span>
    <span class="grow">
      <span style="display:block;font-weight:650;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(result.title || '(sans titre)')}</span>
      <span class="tiny muted">${esc(result.subtitle ?? '')}</span>
    </span>
    <span class="kind">${label}</span>
  </button>`;
}

function searchView() {
  const { q, results, busy, error } = S.search;
  return shell(`
    <form data-form="search" class="search-bar" role="search">
      ${ICON.search}
      <input id="search-q" name="q" value="${esc(q)}" placeholder="Personnes, groupes, publications" aria-label="Rechercher dans TOMATE" autocomplete="off" />
      <button class="btn btn-primary" type="submit">Chercher</button>
    </form>
    ${error ? notice(error, 'error', true) : ''}
    ${busy ? '<div class="card flat" style="margin-top:12px"><p class="muted small">Recherche en cours…</p></div>' : ''}
    ${!busy && results.length > 0
      ? `<div class="card" style="margin-top:12px;padding:6px 16px">${results.map(searchResult).join('')}</div>`
      : ''}
    ${!busy && q.length >= 2 && results.length === 0 && !error
      ? `<div class="card" style="margin-top:12px">${emptyState('🔎', 'Aucun résultat.', 'Essayez un autre pseudo, un nom de groupe ou un mot de publication.')}</div>`
      : ''}
    ${q.length < 2
      ? `<div class="card flat" style="margin-top:12px">${emptyState('🔎', 'Chercher dans TOMATE.', 'Pseudo, nom de groupe ou mot d’une publication. Hors ligne, la recherche se limite à ce qui est déjà sur cet appareil.')}</div>`
      : ''}
  `, { title: 'Rechercher', back: '#/feed' });
}

function localSearch(term) {
  const needle = term.toLowerCase();
  const people = S.profile && `${S.profile.handle} ${S.profile.display_name}`.toLowerCase().includes(needle)
    ? [{ kind: 'person', id: S.profile.id, title: S.profile.display_name, subtitle: `@${S.profile.handle}`, handle: S.profile.handle }]
    : [];
  return [
    ...people,
    ...S.communities
      .filter((c) => `${c.name} ${c.slug}`.toLowerCase().includes(needle))
      .map((c) => ({ kind: 'community', id: c.id, title: c.name, subtitle: `${c.member_count ?? 0} membre(s) · ${c.visibility === 'Private' ? 'privé' : 'public'}` })),
    ...S.feed
      .filter((p) => (p.body ?? '').toLowerCase().includes(needle))
      .slice(0, 12)
      .map((p) => ({ kind: 'post', id: p.id, title: p.body?.slice(0, 90) || 'Média', subtitle: `@${p.author_handle ?? ''}` })),
  ].slice(0, 30);
}

async function runSearch(term) {
  const q = String(term ?? '').trim();
  S.search.q = q;
  S.search.error = null;
  if (q.length < 2) {
    S.search.results = [];
    render();
    return;
  }
  if (!ONLINE()) {
    S.search.results = localSearch(q);
    S.search.error = 'Hors ligne : résultats limités aux données déjà téléchargées sur cet appareil.';
    render();
    return;
  }
  S.search.busy = true;
  render();
  try {
    // PostgREST uses `*` as its wildcard; strip characters that would break the filter.
    const needle = q.replace(/[*,()%]/g, ' ').trim() || q;
    const enc = encodeURIComponent(needle);
    const [people, groups, posts] = await Promise.all([
      rest('profiles', { query: `select=id,handle,display_name&or=(handle.ilike.*${enc}*,display_name.ilike.*${enc}*)&limit=8` }),
      rest('communities', { query: `select=id,name,member_count,visibility&or=(name.ilike.*${enc}*,slug.ilike.*${enc}*)&limit=8` }),
      rest('feed', { query: `select=id,body,author_handle,author_display_name&body=ilike.*${enc}*&limit=12` }),
    ]);
    S.search.results = [
      ...(people ?? []).map((p) => ({ kind: 'person', id: p.id, title: p.display_name || p.handle, subtitle: `@${p.handle}`, handle: p.handle })),
      ...(groups ?? []).map((c) => ({ kind: 'community', id: c.id, title: c.name, subtitle: `${c.member_count ?? 0} membre(s) · ${c.visibility === 'Private' ? 'privé' : 'public'}` })),
      ...(posts ?? []).map((p) => ({ kind: 'post', id: p.id, title: p.body?.slice(0, 90) || 'Média', subtitle: `@${p.author_handle ?? ''}` })),
    ];
  } catch (error) {
    S.search.error = humanError(error);
    S.search.results = localSearch(q);
  } finally {
    S.search.busy = false;
    render();
  }
}

// -------------------------------------------------------------- communautés

function communitiesView() {
  return shell(`
    ${S.lastError ? notice(S.lastError, 'error', true) : ''}
    <button class="search-bar" style="width:100%;margin-bottom:14px" data-act="nav" data-route="#/search">
      ${ICON.search}
      <span class="grow" style="text-align:left;color:var(--muted);padding:10px 0">Rechercher un groupe, une personne…</span>
    </button>
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
    const cachedCommunities = await cacheGet('communities');
    if (Array.isArray(cachedCommunities)) S.communities = cachedCommunities;
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
  // Le trigger `tomate_community_owner_membership` ajoute déjà le créateur en Owner :
  // une seconde insertion « Owner » était refusée par la RLS (403) et salissait la file.
  await enqueue('community_create', { id: community.id, name: community.name, slug: community.slug, description: '', owner_id: S.profile.id, visibility: 'Public' });
  render();
  await syncNow();
}

async function joinCommunity(id) {
  const community = S.communities.find((c) => c.id === id);
  if (!community) return;
  // Public → membre actif. Privé → demande en attente, jamais une adhésion silencieuse.
  const pending = community.visibility === 'Private';
  if (pending) {
    community.joinStatus = 'Pending';
  } else {
    S.memberships.add(id);
    community.member_count = (community.member_count ?? 0) + 1;
  }
  await cacheSet('communities', S.communities);
  await cacheSet('memberships', [...S.memberships]);
  await enqueue('community_join', {
    community_id: id,
    user_id: S.profile.id,
    role: 'Member',
    status: pending ? 'Pending' : 'Active',
  });
  render();
  await syncNow();
  toast(pending ? 'Demande envoyée : un modérateur doit l’accepter.' : 'Vous avez rejoint la communauté.');
}

async function loadCommunity(id) {
  if (!ONLINE()) return;
  try {
    await ensureRemoteSession();
    const rows = await rest('community_members', {
      query: `select=user_id,role,status,profile:profiles(handle,display_name)&community_id=eq.${id}&order=role.asc&limit=60`,
    });
    S.communityMembers[id] = rows;
    const fresh = await rest('communities', { query: `select=id,member_count,description&id=eq.${id}` });
    const row = fresh?.[0];
    const community = S.communities.find((c) => c.id === id);
    if (row && community) {
      community.member_count = row.member_count;
      community.description = row.description ?? community.description;
      await cacheSet('communities', S.communities);
    }
    render();
  } catch (error) {
    // The cached community and posts remain usable; the member list simply stays empty.
    S.lastError = ONLINE() ? humanError(error) : null;
  }
}

function communityView() {
  const community = S.communities.find((c) => c.id === S.param);
  if (!community) {
    return shell(`<div class="card">${emptyState('🌍', 'Communauté introuvable en cache.', 'Ouvrez-la depuis la liste ou demandez un lien d’invitation.')}</div>`, { title: 'Communauté', back: '#/communities' });
  }
  const member = S.memberships.has(community.id);
  const posts = S.feed.filter((p) => p.community_id === community.id);
  const members = S.communityMembers[community.id] ?? [];
  const pending = community.joinStatus === 'Pending';
  return shell(`
    ${S.lastError ? notice(S.lastError, 'error', true) : ''}
    <div class="community-hero">
      <span class="avatar lg">${initials(community.name)}</span>
      <div class="grow">
        <h2>${esc(community.name)}</h2>
        <div class="small muted">${community.member_count ?? 0} membre(s) · ${community.visibility === 'Private' ? 'privé' : 'public'}</div>
        <div class="pill-list" style="margin-top:9px">
          ${member
            ? '<span class="badge ok">Membre</span>'
            : pending
              ? '<span class="badge warn">Demande envoyée</span>'
              : `<button class="btn btn-primary" data-act="join-community" data-id="${esc(community.id)}">Rejoindre</button>`}
          <button class="btn btn-soft" data-act="copy-invite" data-id="${esc(community.id)}">${ICON.link} Inviter</button>
        </div>
      </div>
    </div>
    <p class="muted" style="margin:14px 4px 0">${esc(community.description || 'Pas encore de description.')}</p>

    ${member
      ? `<div class="card" style="margin-top:12px">
          <form data-form="publish-community" data-community-id="${esc(community.id)}" class="compose">
            <label for="compose-${esc(community.id)}" class="composer-target">${ICON.communities} Dans ${esc(community.name)}</label>
            <textarea id="compose-${esc(community.id)}" name="body" maxlength="${MAX_POST_LENGTH}" placeholder="Écrire au groupe…">${esc(readDraft(community.id))}</textarea>
            <div class="row-between">
              <div class="row" style="gap:8px">
                <span class="tiny" data-counter="community">0/${MAX_POST_LENGTH}</span>
                <button class="btn btn-soft btn-sm" type="button" data-act="event-new" data-id="${esc(community.id)}">Événement</button>
              </div>
              <button class="btn btn-primary" type="submit">Publier</button>
            </div>
          </form>
        </div>`
      : `<div class="card flat" style="margin-top:12px">${emptyState('✍️', 'Rejoignez la communauté pour publier.', 'La lecture du groupe reste possible ; l’écriture demande l’adhésion.')}</div>`}

    <div class="section-title"><h2>Publications</h2>
      <button class="icon-btn" data-act="refresh" aria-label="Actualiser">${ICON.refresh}</button>
    </div>
    <div class="card" style="padding:6px 16px">
      ${posts.length === 0
        ? emptyState('📝', 'Aucune publication ici.', 'Soyez le premier à écrire dans ce groupe.')
        : posts.map(postItem).join('')}
    </div>

    ${members.length > 0
      ? `<div class="section-title"><h2>Membres (${members.length})</h2></div>
         <div class="card" style="padding:10px 18px">
           ${members.map((m) => `<div class="member-row">
             <span class="avatar" style="width:36px;height:36px;font-size:13px;border-radius:12px">${initials(m.profile?.display_name || m.profile?.handle || '?')}</span>
             <div class="grow"><div style="font-weight:600">${esc(m.profile?.display_name || m.profile?.handle || 'Membre')}</div>
             <div class="tiny muted">@${esc(m.profile?.handle ?? '')}</div></div>
             <span class="member-role ${m.role === 'Owner' ? 'owner' : ''}">${esc(m.role)}</span>
             ${m.status === 'Pending' ? '<span class="badge warn">en attente</span>' : ''}
           </div>`).join('')}
         </div>`
      : ''}
  `, { title: community.name, back: '#/communities' });
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
    text: 'La session, le profil et toutes les données locales (publications en attente, caches) seront supprimés de cet appareil. Sans e-mail et mot de passe, le compte ne pourra pas être restauré.',
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
  S.notifications = [];
  S.unread = 0;
  S.notificationsReady = null;
  S.authEmail = '';
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
      <h2 style="margin-bottom:10px">Compte et récupération</h2>
      ${S.authEmail
        ? `<p class="small">Compte protégé par <strong>${esc(S.authEmail)}</strong>. Sur un autre appareil, « J'ai déjà un compte » avec cet e-mail et votre mot de passe restaure ce compte et ses données.</p>
           <button class="btn btn-soft btn-block" data-act="nav" data-route="#/profile">Voir mon profil</button>`
        : `<p class="small muted">Votre compte est anonyme : s'il est perdu, il l'est définitivement. Ajoutez un e-mail et un mot de passe pour le retrouver sur un autre appareil.</p>
           <form data-form="secure-account" class="col">
             <div class="field">
               <label for="secure-email">E-mail de connexion</label>
               <input class="input" id="secure-email" name="email" type="email" required placeholder="vous@exemple.fr" autocomplete="email" />
             </div>
             <div class="field">
               <label for="secure-password">Mot de passe (8 caractères minimum)</label>
               <input class="input" id="secure-password" name="password" type="password" required minlength="8" placeholder="Mot de passe" autocomplete="new-password" />
             </div>
             <button class="btn btn-primary btn-block" type="submit">Protéger mon compte</button>
           </form>
           <p class="hint">Cet e-mail sert d'identifiant de connexion ; aucun message ne vous sera envoyé.</p>`}
    </div>

    <div class="card">
      <h2 style="margin-bottom:10px">Notifications et stockage</h2>
      <div class="settings-row"><div><strong>Notifications</strong><div class="tiny muted">${S.unread > 0 ? `${S.unread} non lue(s)` : 'Messages, réactions et adhésions'}</div></div>
        <button class="btn btn-soft" data-act="nav" data-route="#/notifications">Ouvrir</button></div>
      <div class="settings-row"><div><strong>Test local</strong><div class="tiny muted">Autorisation demandée au moment de l'action</div></div>
        <button class="btn btn-soft" data-act="notify-test" ${caps.notifications ? '' : 'disabled'}>Tester</button></div>
      <div class="settings-row"><div><strong>Stockage</strong><div class="tiny muted">${S.storage ? `${S.storage.usageMb} Mo utilisés sur ${S.storage.quotaMb} Mo` : 'estimation indisponible'}</div></div>
        <button class="btn btn-soft" data-act="storage">Estimer</button></div>
      <div class="settings-row"><div><strong>Exporter mes données</strong><div class="tiny muted">Profil, publications, stories et messages envoyés, en JSON</div></div>
        <button class="btn btn-soft" data-act="export-data">${ICON.download} Exporter</button></div>
      <div class="settings-row"><div><strong>Vider le cache de contenu</strong><div class="tiny muted">Conserve le compte et la file d'attente</div></div>
        <button class="btn btn-danger" data-act="clear-cache">Vider</button></div>
    </div>

    <div class="card">
      <h2 style="margin-bottom:10px">Limites annoncées</h2>
      <ul class="small muted" style="padding-left:18px;line-height:1.6">
        <li>Pas de mesh BLE / Wi-Fi Direct ni de relais en arrière-plan dans un navigateur : l'app Android reste la référence.</li>
        <li>Chiffrement de bout en bout : absent. Aucun libellé ne le prétend.</li>
        <li>Récupération : possible avec l'e-mail et le mot de passe ajoutés ici ; sans eux, un compte anonyme est perdu avec l'appareil.</li>
        <li>Météo : Open-Meteo, mise en cache datée.</li>
        <li>Notifications : visibles dans l'application (écrites par le serveur). L'alerte sur un appareil fermé (Web Push / FCM) n'est pas encore activée.</li>
      </ul>
    </div>

    <div class="card">
      <div class="row-between"><div><strong>Se déconnecter</strong><div class="tiny muted">Efface aussi les données locales</div></div>
        <button class="btn btn-danger" data-act="sign-out">Se déconnecter</button></div>
    </div>
    <p class="tiny muted center" style="margin-top:16px">TOMATE ${APP_VERSION} · client web · backend Supabase</p>
  `, { title: 'Paramètres', back: '#/profile' });
}

async function exportMyData() {
  const session = await ensureRemoteSession();
  if (!session) return toast('Connexion requise pour exporter vos données.');
  toast('Préparation de l’export…');
  try {
    const owner = `author_id=eq.${session.userId}`;
    const [profile, posts, stories, messages] = await Promise.all([
      rest('profiles', { query: `select=*&id=eq.${session.userId}` }),
      rest('posts', { query: `select=*&${owner}&order=created_at.desc&limit=1000` }),
      rest('stories', { query: `select=*&${owner}&order=created_at.desc&limit=1000` }),
      rest('messages', {
        query: `select=*&sender_id=eq.${session.userId}&order=created_at.desc&limit=5000`,
      }),
    ]);
    const payload = {
      exported_at: new Date().toISOString(),
      app: `TOMATE ${APP_VERSION}`,
      profile: profile[0] ?? null,
      posts,
      stories,
      sent_messages: messages,
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `tomate-export-${new Date().toISOString().slice(0, 10)}.json`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    toast(`${posts.length} publication(s), ${stories.length} story(s), ${messages.length} message(s) exportés.`);
  } catch (error) {
    toast(humanError(error));
  }
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
    const freshStories = await cacheGet('stories');
    if (Array.isArray(freshStories)) S.stories = freshStories.filter(storyAlive);
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
    // Every route may show pending/failed badges: always repaint after a flush.
    render();
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

// -------------------------------------------------------------- notifications

/**
 * Notifications are written by the server (0007): the client only reads them.
 * An older backend without the table answers 404, which is reported honestly.
 */
async function loadNotifications() {
  if (!S.profile || S.session?.local || !ONLINE()) return;
  const before = S.unread;
  try {
    const rows = (await rest('notifications', { query: 'select=*&order=created_at.desc&limit=40' })) ?? [];
    S.notifications = rows;
    S.unread = rows.filter((row) => !row.read_at).length;
    S.notificationsReady = true;
    S.notificationsError = null;
    const ids = [...new Set(rows.map((row) => row.actor_id).filter(Boolean))];
    if (ids.length) {
      const profiles = (await rest('profiles', {
        query: `select=id,handle,display_name&id=in.(${ids.join(',')})`,
      })) ?? [];
      S.notifActors = Object.fromEntries(profiles.map((p) => [p.id, p]));
    }
  } catch (error) {
    S.notificationsReady = false;
    S.notificationsError = error instanceof HttpError && error.status === 404
      ? 'Notifications pas encore activées : appliquez la migration 0007_compte_notifications.sql.'
      : humanError(error);
  }
  if (S.route === 'notifications' || S.unread !== before) render();
}

async function markNotificationsRead() {
  if (!S.notifications.some((row) => !row.read_at)) return;
  try {
    await rest('notifications', {
      method: 'PATCH',
      query: 'read_at=is.null',
      body: { read_at: new Date().toISOString() },
    });
  } catch (error) {
    return toast(humanError(error));
  }
  const now = new Date().toISOString();
  S.notifications = S.notifications.map((row) => ({ ...row, read_at: row.read_at ?? now }));
  S.unread = 0;
  render();
}

const NOTIF_LABEL = {
  message: 'vous a envoyé un message',
  reaction: 'a réagi à votre publication',
  comment: 'a commenté votre publication',
  join: 'groupe',
  story: 'a publié une story',
  system: 'information',
};

function notifRoute(row) {
  if (row.kind === 'message' && row.conversation_id) return `#/chat/${row.conversation_id}`;
  if ((row.kind === 'reaction' || row.kind === 'comment') && row.entity_id) return `#/post/${row.entity_id}`;
  if (row.kind === 'join' && row.entity_id) return `#/community/${row.entity_id}`;
  return null;
}

function notificationsView() {
  if (S.notificationsError) {
    return shell(`${notice(S.notificationsError, 'warning')}
      <div class="card"><p class="small muted">Le serveur crée ces notifications automatiquement à chaque message, réaction, commentaire ou demande d'adhésion. Aucune n'est inventée côté client.</p></div>`,
    { title: 'Notifications', back: '#/feed' });
  }
  if (S.notificationsReady === null) {
    return shell(`<div class="card"><p class="muted">Chargement…</p></div>`, { title: 'Notifications', back: '#/feed' });
  }
  if (S.notifications.length === 0) {
    return shell(`${emptyState('🔔', 'Aucune notification.', "Messages, réactions, commentaires et demandes d'adhésion apparaîtront ici.")}`,
      { title: 'Notifications', back: '#/feed' });
  }
  return shell(`
    <div class="row-between" style="margin:4px 2px 12px">
      <span class="small muted">${S.unread > 0 ? `${S.unread} non lue(s)` : 'Tout est lu'}</span>
      ${S.unread > 0 ? '<button class="btn-link" data-act="mark-read">Tout marquer comme lu</button>' : ''}
    </div>
    <div class="card" style="padding:4px 12px">
      ${S.notifications.map((row) => {
        const actor = S.notifActors[row.actor_id];
        const route = notifRoute(row);
        const name = actor?.display_name || actor?.handle || 'Quelqu\'un';
        const inner = `<span class="avatar sm">${initials(name)}</span>
          <div class="grow">
            <div class="small"><strong>${esc(name)}</strong> ${esc(NOTIF_LABEL[row.kind] ?? '')}</div>
            ${row.body ? `<div class="tiny muted notif-body">${esc(row.body)}</div>` : ''}
            <div class="tiny muted">${esc(fmtDate(row.created_at))}</div>
          </div>
          ${row.read_at ? '' : '<span class="dot-unread" aria-label="Non lue"></span>'}`;
        return route
          ? `<a class="settings-row notif-row ${row.read_at ? '' : 'unread'}" href="${route}">${inner}</a>`
          : `<div class="settings-row notif-row ${row.read_at ? '' : 'unread'}">${inner}</div>`;
      }).join('')}
    </div>
  `, { title: 'Notifications', back: '#/feed' });
}

// ------------------------------------------------------------------- routage

function parseRoute() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [route, ...rest] = raw.split('/');
  return { route: route || 'feed', param: rest.length ? decodeURIComponent(rest.join('/')) : null };
}

async function navigate() {
  const { route, param } = parseRoute();
  if (route !== 'chat') stopCallWatch();
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
      await Promise.all([loadFeed({ refresh: true }), loadStories({ refresh: true })]);
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
      await loadStreaks();
      break;
    case 'chat':
      render();
      // The conversation row carries the title and the creator: without it the header falls back
      // to "Conversation" and the delete action cannot be offered to its owner.
      await loadConversations({ refresh: true });
      await loadMessages(S.param);
      await loadStreaks();
      render();
      startCallWatch(S.param);
      break;
    case 'communities':
      render();
      await loadCommunities({ refresh: true });
      break;
    case 'community':
      render();
      await loadCommunity(S.param);
      break;
    case 'search':
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
    case 'notifications':
      render();
      await loadNotifications();
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
    case 'search': return searchView();
    case 'network': return networkView();
    case 'weather': return weatherView();
    case 'notifications': return notificationsView();
    case 'profile': return profileView();
    case 'settings': return settingsView();
    default: return feedView();
  }
}

function render() {
  if (S.booting) {
    app.innerHTML = `<div class="boot"><div class="boot-logo">${LOGO}<span>TOMATE</span></div><p class="muted">Chargement…</p></div>`;
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

document.addEventListener('change', async (event) => {
  if (event.target.id !== 'chat-media-input') return;
  const file = event.target.files?.[0];
  event.target.value = '';
  if (!file) return;
  try {
    const media = await prepareChatMedia(file);
    if (!media) return;
    if (S.chatMedia?.preview) URL.revokeObjectURL(S.chatMedia.preview);
    S.chatMedia = media;
    render();
  } catch (error) {
    toast(humanError(error));
  }
});

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
        await Promise.all([loadFeed({ refresh: true }), loadStories({ refresh: true })]);
        await syncNow({ silent: true });
        render();
        break;
      case 'mark-read':
        await markNotificationsRead();
        break;
      case 'story-new': {
        const draft = await storyComposer();
        if (draft) await publishStory(draft);
        break;
      }
      case 'story-open':
        await openStory(trigger.dataset.id);
        break;
      case 'story-close': {
        const dialog = trigger.closest('dialog');
        if (dialog?.open) dialog.close();
        dialog?.remove();
        break;
      }
      case 'story-delete': {
        const dialog = trigger.closest('dialog');
        if (await deleteStory(trigger.dataset.id)) {
          if (dialog?.open) dialog.close();
          dialog?.remove();
        }
        break;
      }
      case 'refresh-messages':
        await loadConversations({ refresh: true });
        await loadStreaks();
        render();
        break;
      case 'chat-attach':
        document.getElementById('chat-media-input')?.click();
        break;
      case 'chat-drop-media':
        if (S.chatMedia?.preview) URL.revokeObjectURL(S.chatMedia.preview);
        S.chatMedia = null;
        render();
        break;
      case 'export-data':
        await exportMyData();
        break;
      case 'leave-chat': {
        const id = S.param;
        const ok = await confirmDialog({
          title: 'Quitter la conversation ?',
          text: 'Elle disparaîtra de votre liste. Les messages déjà envoyés restent visibles pour les autres participants.',
          confirmLabel: 'Quitter',
        });
        if (!ok) break;
        try {
          await rest('conversation_members', {
            method: 'DELETE',
            query: `conversation_id=eq.${id}&user_id=eq.${S.profile.id}`,
          });
          // PostgREST answers 204 even when RLS filtered every row: verify instead of trusting it.
          const rows = await rest('conversation_members', {
            query: `select=conversation_id&conversation_id=eq.${id}&user_id=eq.${S.profile.id}`,
          });
          if (rows && rows.length > 0) throw new Error('Le serveur a refusé de vous retirer de cette conversation.');
          S.conversations = S.conversations.filter((c) => c.id !== id);
          await cacheSet('conversations', S.conversations);
          toast('Conversation quittée.');
          location.hash = '#/messages';
        } catch (error) {
          toast(humanError(error));
        }
        break;
      }
      case 'delete-chat': {
        const id = S.param;
        const ok = await confirmDialog({
          title: 'Supprimer pour tout le monde ?',
          text: 'La conversation et tous ses messages seront supprimés pour chaque participant. Cette action est définitive.',
          confirmLabel: 'Supprimer',
          danger: true,
        });
        if (!ok) break;
        try {
          await rest('conversations', { method: 'DELETE', query: `id=eq.${id}` });
          const rows = await rest('conversations', { query: `select=id&id=eq.${id}` });
          if (rows && rows.length > 0) {
            throw new Error('Suppression refusée par le serveur : appliquez la migration 0008_conversations_delete.sql.');
          }
          S.conversations = S.conversations.filter((c) => c.id !== id);
          await cacheSet('conversations', S.conversations);
          await cacheSet(`messages:${id}`, []);
          toast('Conversation supprimée.');
          location.hash = '#/messages';
        } catch (error) {
          toast(humanError(error));
        }
        break;
      }
      case 'poll-new': {
        const poll = await pollDialog();
        if (poll) await sendMessage(S.param, encodePoll(poll), null);
        break;
      }
      case 'poll-vote': {
        if (!isVote(S.messages.length ? '' : '')) { /* garde de lisibilité */ }
        await sendMessage(S.param, encodeVote(trigger.dataset.id, Number(trigger.dataset.index)), null);
        break;
      }
      case 'event-new':
        await createEvent(trigger.dataset.id ?? null);
        break;
      case 'event-rsvp': {
        const post = S.feed.find((p) => p.id === trigger.dataset.id);
        if (post) await toggleRsvp(post);
        break;
      }
      case 'call-audio':
        await startCall({ video: false });
        break;
      case 'call-video':
        await startCall({ video: true });
        break;
      case 'call-hangup':
        endCall('terminé');
        break;
      case 'call-mute': {
        const state = S.call;
        if (!state) break;
        state.muted = !state.muted;
        state.session.setMuted(state.muted);
        trigger.setAttribute('aria-pressed', String(state.muted));
        trigger.textContent = state.muted ? 'Micro coupé' : 'Micro';
        break;
      }
      case 'call-camera': {
        const state = S.call;
        if (!state) break;
        state.cameraOff = !state.cameraOff;
        state.session.setCameraOff(state.cameraOff);
        trigger.setAttribute('aria-pressed', String(state.cameraOff));
        trigger.textContent = state.cameraOff ? 'Caméra coupée' : 'Caméra';
        break;
      }
      case 'qr-add':
        qrDialog();
        break;
      case 'qr-scan':
        await scanFriendCode(trigger.closest('dialog'));
        break;
      case 'qr-copy':
        try {
          await navigator.clipboard.writeText(friendPayload());
          toast('Lien copié.');
        } catch {
          toast('Copie impossible : sélectionnez le lien à la main.');
        }
        break;
      case 'qr-open': {
        const dialog = trigger.closest('dialog');
        const value = dialog.querySelector('#qr-link')?.value ?? '';
        if (!value.trim()) return toast('Collez un lien TOMATE.');
        dialog.close();
        dialog.remove();
        await addFriendFromText(value);
        break;
      }
      case 'refresh-communities':
        await loadCommunities({ refresh: true });
        render();
        break;
      case 'copy-invite': {
        const url = `${location.origin}${location.pathname}#/community/${trigger.dataset.id}`;
        try {
          await navigator.clipboard.writeText(url);
          toast('Lien d’invitation copié.');
        } catch {
          toast(url);
        }
        break;
      }
      case 'drop-media':
        S.postMedia = null;
        render();
        break;
      case 'search-person':
        await createConversationWith({
          id: trigger.dataset.id,
          handle: trigger.dataset.handle ?? '',
          name: trigger.dataset.name ?? '',
        });
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
  // Drafts survive a reload, a tab close and a failed send.
  if (form?.dataset.form === 'publish') {
    S.draft = field.value;
    writeDraft(null, field.value);
  }
  if (form?.dataset.form === 'publish-community') {
    const cid = form.dataset.communityId;
    S.draftCommunity[cid] = field.value;
    writeDraft(cid, field.value);
  }
});

document.addEventListener('change', async (event) => {
  if (!event.target.matches('#post-file')) return;
  const file = event.target.files?.[0];
  if (!file) return;
  try {
    await attachPostMedia(file);
  } catch (error) {
    S.lastError = humanError(error);
    render();
  }
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
        await publishPost(String(data.get('body') ?? ''), { media: S.postMedia });
        form.reset();
        break;
      case 'publish-community':
        await publishPost(String(data.get('body') ?? ''), {
          communityId: form.dataset.communityId,
          media: null,
        });
        form.reset();
        break;
      case 'search':
        await runSearch(String(data.get('q') ?? ''));
        break;
      case 'comment':
        await addComment(form.dataset.postId, String(data.get('body') ?? ''));
        form.reset();
        break;
      case 'send-message':
        await sendMessage(form.dataset.conversation, String(data.get('body') ?? ''), S.chatMedia);
        form.reset();
        break;
      case 'signin': {
        const email = String(data.get('email') ?? '').trim().toLowerCase();
        const password = String(data.get('password') ?? '');
        if (!email || !password) throw new Error('E-mail et mot de passe obligatoires.');
        const session = await signInWithPassword(email, password);
        S.session = session;
        const profile = await fetchMyProfile();
        if (!profile) throw new Error("Ce compte n'a pas encore de profil TOMATE sur ce serveur.");
        S.profile = profile;
        S.authEmail = session.email ?? email;
        await setItem('kv', 'profile', profile);
        toast('Compte restauré sur cet appareil.');
        location.hash = '#/feed';
        S.route = 'feed';
        await navigate();
        break;
      }
      case 'secure-account': {
        const email = String(data.get('email') ?? '').trim().toLowerCase();
        const password = String(data.get('password') ?? '');
        if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw new Error('Adresse e-mail invalide.');
        if (password.length < 8) throw new Error('Mot de passe : 8 caractères minimum.');
        const user = await attachCredentials(email, password);
        const keptEmail = user.email ?? email;
        await rememberEmail(keptEmail);
        S.session = { ...S.session, email: keptEmail };
        S.authEmail = keptEmail;
        toast('Compte protégé : connectez-vous avec cet e-mail sur un autre appareil.');
        render();
        break;
      }
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

// A 24-hour story must disappear on screen at the right minute, not only on next launch.
setInterval(() => {
  if (S.profile && navigator.onLine) loadNotifications();
}, 45_000);

setInterval(async () => {
  const alive = S.stories.filter(storyAlive);
  if (alive.length === S.stories.length) return;
  S.stories = alive;
  await cacheSet('stories', S.stories);
  if (S.route === 'feed') render();
}, 60_000);

async function boot() {
  try {
    await applyTheme(await getItem('kv', 'theme'));
    S.session = await ensureActor();
    S.authEmail = S.session?.email ?? '';
    S.profile = await getItem('kv', 'profile');
    S.weather = await cachedWeather();
    const cachedFeed = await cacheGet('feed');
    if (Array.isArray(cachedFeed)) S.feed = cachedFeed;
    const cachedStories = await cacheGet('stories');
    if (Array.isArray(cachedStories)) S.stories = cachedStories.filter(storyAlive);
    const cachedMemberships = await cacheGet('memberships');
    if (Array.isArray(cachedMemberships)) S.memberships = new Set(cachedMemberships);
    const cachedCommunities = await cacheGet('communities');
    if (Array.isArray(cachedCommunities)) S.communities = cachedCommunities;
    await refreshCounts();
  } catch (error) {
    S.lastError = humanError(error);
  } finally {
    S.booting = false;
    if (!S.profile) S.route = 'welcome';
    await navigate();
    if (navigator.onLine) await syncNow({ silent: true });
    if (S.profile) await loadNotifications();
  }
}

if ('serviceWorker' in navigator) {
  // A deploy replaces the shell, but an already-open page keeps the old worker until it is
  // reloaded: that is how a user ends up running code that is no longer deployed. When a new
  // worker takes control, reload once so the page always matches the deployed version.
  const controlledAtBoot = !!navigator.serviceWorker.controller;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (!controlledAtBoot) return;
    if (sessionStorage.getItem('tomate:shell-reloaded') === '1') return;
    sessionStorage.setItem('tomate:shell-reloaded', '1');
    location.reload();
  });
  window.addEventListener('load', async () => {
    try {
      const registration = await navigator.serviceWorker.register('/sw.js');
      registration.addEventListener('updatefound', () => {
        toast('Mise à jour TOMATE en cours d’application…');
      });
      // Browsers may reuse a cached worker script for up to 24 h. `update()` bypasses that cache,
      // so a deployment is picked up at the next launch instead of the next day.
      registration.update().catch(() => {});
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') registration.update().catch(() => {});
      });
    } catch {
      // The app still works without a service worker (no offline cache).
    }
  });
}

boot();


// ------------------------------------------------------------------ sondages

/** Un vote et une ligne de signalisation vivent en base mais ne s'affichent jamais comme un message. */
function isSignalMessage(body) {
  return typeof body === 'string' && body.startsWith(CALL_PREFIX);
}

function visibleMessages() {
  return S.messages.filter((m) => !isVote(m.body) && !isSignalMessage(m.body));
}

function pollDialog() {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.innerHTML = `
      <h2>Nouveau sondage</h2>
      <div class="field" style="margin-top:10px">
        <label for="poll-question">Question</label>
        <input class="input" id="poll-question" maxlength="120" placeholder="On mange où ?" />
      </div>
      <div class="field">
        <label>Options (deux au minimum)</label>
        ${[0, 1, 2, 3].map((i) => `<input class="input poll-input" maxlength="60" placeholder="Option ${i + 1}" style="margin-bottom:6px" aria-label="Option ${i + 1}" />`).join('')}
      </div>
      <p class="tiny" data-poll-error style="color:var(--danger);margin:0 0 8px"></p>
      <div class="row">
        <button class="btn btn-soft" data-choice="cancel">Annuler</button>
        <button class="btn btn-primary" data-choice="ok">Créer le sondage</button>
      </div>`;
    document.body.appendChild(dialog);
    dialog.showModal();
    dialog.querySelector('#poll-question').focus();
    const error = dialog.querySelector('[data-poll-error]');
    const done = (ok) => {
      if (!ok) {
        dialog.close();
        dialog.remove();
        resolve(null);
        return;
      }
      const question = dialog.querySelector('#poll-question').value.trim();
      const options = [...dialog.querySelectorAll('.poll-input')].map((n) => n.value.trim()).filter(Boolean);
      if (!question) {
        error.textContent = 'Écrivez une question.';
        return;
      }
      if (options.length < 2) {
        error.textContent = 'Il faut au moins deux options.';
        return;
      }
      dialog.close();
      dialog.remove();
      resolve({ question, options });
    };
    dialog.addEventListener('click', (event) => {
      const choice = event.target.closest('[data-choice]')?.dataset.choice;
      if (choice) done(choice === 'ok');
    });
    dialog.addEventListener('cancel', () => {
      dialog.remove();
      resolve(null);
    });
  });
}

function pollBubble(message, poll) {
  const mine = message.sender_id === S.profile?.id;
  const tally = tallyPoll(S.messages, message.id, S.profile?.id);
  return `<div class="poll-card${mine ? ' mine' : ''}">
    <div class="poll-question">${esc(poll.question)}</div>
    <div class="poll-options">
      ${poll.options.map((option, index) => {
        const count = tally.counts.get(index) ?? 0;
        const share = tally.total ? Math.round((count / tally.total) * 100) : 0;
        const chosen = tally.mine === index;
        return `<button class="poll-option${chosen ? ' chosen' : ''}" data-act="poll-vote" data-id="${esc(message.id)}" data-index="${index}" aria-pressed="${chosen}">
          <span class="poll-share" style="width:${share}%"></span>
          <span class="poll-label">${esc(option)}</span>
          <span class="poll-count">${count}</span>
        </button>`;
      }).join('')}
    </div>
    <div class="tiny muted">${tally.total ? `${tally.total} votant(s)` : 'Aucun vote'} · ${fmtDate(message.created_at)}</div>
  </div>`;
}

// --------------------------------------------------------------- événements

function eventDialog() {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.innerHTML = `
      <h2>Nouvel événement</h2>
      <div class="field" style="margin-top:10px">
        <label for="event-title">Titre</label>
        <input class="input" id="event-title" maxlength="120" placeholder="Café du quartier" />
      </div>
      <div class="field">
        <label for="event-date">Date et heure</label>
        <input class="input" id="event-date" type="datetime-local" />
      </div>
      <div class="field">
        <label for="event-place">Lieu (facultatif)</label>
        <input class="input" id="event-place" maxlength="120" placeholder="Place du marché" />
      </div>
      <div class="field">
        <label for="event-details">Détails (facultatif)</label>
        <textarea class="textarea" id="event-details" maxlength="400" placeholder="Ce qu'il faut apporter…"></textarea>
      </div>
      <p class="tiny" data-event-error style="color:var(--danger);margin:0 0 8px"></p>
      <div class="row">
        <button class="btn btn-soft" data-choice="cancel">Annuler</button>
        <button class="btn btn-primary" data-choice="ok">Publier l'événement</button>
      </div>`;
    document.body.appendChild(dialog);
    dialog.showModal();
    dialog.querySelector('#event-title').focus();
    const error = dialog.querySelector('[data-event-error]');
    const done = (ok) => {
      if (!ok) {
        dialog.close();
        dialog.remove();
        resolve(null);
        return;
      }
      const title = dialog.querySelector('#event-title').value.trim();
      const startsAt = localInputToIso(dialog.querySelector('#event-date').value);
      if (!title) {
        error.textContent = 'Donnez un titre à l’événement.';
        return;
      }
      if (!startsAt) {
        error.textContent = 'Choisissez une date et une heure valides.';
        return;
      }
      dialog.close();
      dialog.remove();
      resolve({
        title,
        startsAt,
        place: dialog.querySelector('#event-place').value.trim(),
        details: dialog.querySelector('#event-details').value.trim(),
      });
    };
    dialog.addEventListener('click', (event) => {
      const choice = event.target.closest('[data-choice]')?.dataset.choice;
      if (choice) done(choice === 'ok');
    });
    dialog.addEventListener('cancel', () => {
      dialog.remove();
      resolve(null);
    });
  });
}

function eventCard(post, event) {
  const rsvp = S.rsvps[post.id] ?? { count: 0, mine: false };
  const date = new Date(event.startsAt);
  const day = date.toLocaleDateString('fr-FR', { day: '2-digit' });
  const month = date.toLocaleDateString('fr-FR', { month: 'short' });
  return `<div class="event-card">
    <div class="event-date"><span class="event-day">${esc(day)}</span><span class="event-month">${esc(month)}</span></div>
    <div class="grow">
      <div class="event-title">${esc(event.title)}</div>
      <div class="small muted">${esc(formatEventDate(event.startsAt))}</div>
      ${event.place ? `<div class="tiny muted">${esc(event.place)}</div>` : ''}
      ${event.details ? `<div class="small" style="margin-top:6px">${esc(event.details)}</div>` : ''}
      <div class="row" style="margin-top:9px;gap:8px;align-items:center">
        <button class="btn btn-sm ${rsvp.mine ? 'btn-primary' : 'btn-soft'}" data-act="event-rsvp" data-id="${esc(post.id)}" aria-pressed="${rsvp.mine}">
          ${rsvp.mine ? 'Je participe' : 'Participer'}
        </button>
        <span class="tiny muted">${rsvp.count} participant(s)</span>
      </div>
    </div>
  </div>`;
}

async function loadRsvps(posts) {
  const ids = (posts ?? []).filter((post) => decodeEvent(post.body)).map((post) => post.id);
  if (!ids.length) return;
  try {
    const rows = await rest('post_reactions', {
      query: `select=post_id,user_id&kind=eq.participe&post_id=in.(${ids.join(',')})`,
    });
    const next = {};
    for (const id of ids) next[id] = { count: 0, mine: false };
    for (const row of rows) {
      if (!next[row.post_id]) continue;
      next[row.post_id].count += 1;
      if (row.user_id === S.profile?.id) next[row.post_id].mine = true;
    }
    S.rsvps = { ...S.rsvps, ...next };
  } catch {
    // Sans réseau, le compte affiché reste celui du cache : il n'est jamais inventé.
  }
}

async function createEvent(communityId) {
  const event = await eventDialog();
  if (!event) return;
  const community = communityId ? S.communities.find((c) => c.id === communityId) : null;
  const post = {
    id: crypto.randomUUID(),
    author_id: S.profile.id,
    author_handle: S.profile.handle,
    author_display_name: S.profile.display_name,
    body: encodeEvent(event),
    community_id: communityId ?? null,
    community_name: community?.name ?? null,
    visibility: communityId ? 'Community' : 'Public',
    created_at: new Date().toISOString(),
    reaction_count: 0,
    comment_count: 0,
    reacted_by_me: false,
    media_path: null,
    media_type: null,
    pending: true,
  };
  S.feed = [post, ...S.feed];
  S.rsvps[post.id] = { count: 1, mine: true };
  await cacheSet('feed', S.feed);
  await enqueue('post_insert', {
    id: post.id,
    author_id: post.author_id,
    body: post.body,
    visibility: post.visibility,
    community_id: communityId ?? null,
  });
  await enqueue('reaction_add', { post_id: post.id, user_id: S.profile.id, kind: 'participe' });
  render();
  await syncNow();
  toast('Événement publié. Les membres peuvent maintenant répondre.');
}

async function toggleRsvp(post) {
  const current = S.rsvps[post.id] ?? { count: 0, mine: false };
  S.rsvps[post.id] = {
    count: Math.max(0, current.count + (current.mine ? -1 : 1)),
    mine: !current.mine,
  };
  render();
  await enqueue(S.rsvps[post.id].mine ? 'reaction_add' : 'reaction_remove',
    { post_id: post.id, user_id: S.profile.id, kind: 'participe' });
  await syncNow();
}

// ---------------------------------------------------------------- appels 1-à-1
//
// La signalisation emprunte les messages de la conversation : pas de table, pas de serveur
// temps réel à installer. Les lignes portent un préfixe et sont supprimées après l'appel.

const CALL_POLL_MS = 2000;
const CALL_WATCH_MS = 3000;

async function signalingSend(conversationId, signal) {
  const session = await ensureRemoteSession();
  if (!session) throw new Error('connexion requise');
  for (const body of packSignal(signal)) {
    await rest('messages', {
      method: 'POST',
      prefer: 'return=minimal',
      body: {
        id: crypto.randomUUID(),
        client_id: crypto.randomUUID(),
        conversation_id: conversationId,
        sender_id: session.userId,
        body,
        encrypted: false,
      },
    });
  }
}

async function signalingSince(conversationId, sinceIso) {
  return rest('messages', {
    query: `select=id,sender_id,body,created_at&conversation_id=eq.${conversationId}&body=like.${encodeURIComponent(`${CALL_PREFIX}%`)}&created_at=gt.${encodeURIComponent(sinceIso)}&order=created_at.asc&limit=50`,
  });
}

async function cleanupSignals(conversationId, sinceIso) {
  await rest('messages', {
    method: 'DELETE',
    query: `conversation_id=eq.${conversationId}&sender_id=eq.${S.profile.id}&body=like.${encodeURIComponent(`${CALL_PREFIX}%`)}&created_at=gte.${encodeURIComponent(sinceIso)}`,
  });
}

function callDialog({ video, status }) {
  const dialog = document.createElement('dialog');
  dialog.className = 'call-dialog';
  dialog.innerHTML = `
    <div class="call-stage">
      <video class="call-remote" autoplay playsinline ${video ? '' : 'hidden'}></video>
      <video class="call-local" autoplay playsinline muted ${video ? '' : 'hidden'}></video>
      <div class="call-glyph" ${video ? 'hidden' : ''}>${ICON.phone}</div>
      <div class="call-status">${esc(status)}</div>
    </div>
    <div class="call-actions">
      <button class="call-btn" data-act="call-mute" aria-pressed="false">Micro</button>
      ${video ? `<button class="call-btn" data-act="call-camera" aria-pressed="false">Caméra</button>` : ''}
      <button class="call-btn danger" data-act="call-hangup">Raccrocher</button>
    </div>`;
  document.body.appendChild(dialog);
  dialog.showModal();
  dialog.addEventListener('cancel', (event) => {
    event.preventDefault();
    endCall('terminé');
  });
  return dialog;
}

function setCallStatus(dialog, state) {
  const label = {
    connexion: 'Connexion…',
    sonnerie: 'Sonnerie…',
    connecté: 'En communication',
    interrompu: 'Connexion interrompue…',
    échoué: 'Appel échoué',
    terminé: 'Appel terminé',
    'annulé': 'Appel annulé',
  }[state] ?? state;
  const node = dialog.querySelector('.call-status');
  if (node) node.textContent = label;
  const glyph = dialog.querySelector('.call-glyph');
  if (glyph) glyph.classList.toggle('live', state === 'connecté');
  if (state === 'échoué') {
    // Un échec ICE est muet sinon : la fenêtre se fermait sans explication.
    toast('Appel échoué : la liaison directe n\'a pas abouti (réseau restrictif ou NAT). Réessayez sur un autre réseau.');
  }
  if (state === 'échoué' || state === 'terminé') endCall(state);
}

function attachStreams(dialog, session) {
  const local = dialog.querySelector('.call-local');
  if (local && session.localStream) local.srcObject = session.localStream;
}

async function startCall({ video }) {
  if (S.call) return toast('Un appel est déjà en cours.');
  if (!ONLINE()) return toast('Appel : une connexion est nécessaire.');
  const conversationId = S.param;
  const dialog = callDialog({ video, status: 'Connexion…' });
  const startedAt = new Date().toISOString();
  try {
    const session = new CallSession({
      transport: { send: (signal) => signalingSend(conversationId, signal) },
      video,
      onState: (state) => setCallStatus(dialog, state),
      onRemote: (stream) => {
        const remote = dialog.querySelector('.call-remote');
        if (remote) {
          remote.srcObject = stream;
          remote.removeAttribute('hidden');
          dialog.querySelector('.call-glyph')?.setAttribute('hidden', '');
        }
      },
    });
    S.call = { session, conversationId, startedAt, dialog, video, timer: null, muted: false, cameraOff: false };
    await session.start();
    attachStreams(dialog, session);
    S.call.timer = setInterval(() => { pollSignals().catch(() => {}); }, CALL_POLL_MS);
  } catch (error) {
    S.call = null;
    dialog.remove();
    const reason = error?.name === 'NotAllowedError'
      ? 'micro ou caméra refusé par le navigateur'
      : error?.name === 'NotFoundError'
        ? 'aucun micro détecté sur cet appareil'
        : error?.message || humanError(error);
    toast(`Appel impossible : ${reason}.`);
  }
}

async function answerCall(offer) {
  const conversationId = S.param;
  const video = Boolean(offer.video);
  const dialog = callDialog({ video, status: 'Réponse…' });
  const startedAt = new Date().toISOString();
  try {
    const session = new CallSession({
      transport: { send: (signal) => signalingSend(conversationId, signal) },
      video,
      onState: (state) => setCallStatus(dialog, state),
      onRemote: (stream) => {
        const remote = dialog.querySelector('.call-remote');
        if (remote) {
          remote.srcObject = stream;
          remote.removeAttribute('hidden');
          dialog.querySelector('.call-glyph')?.setAttribute('hidden', '');
        }
      },
    });
    S.call = { session, conversationId, startedAt, dialog, video, timer: null, muted: false, cameraOff: false };
    await session.handleSignal(offer);
    attachStreams(dialog, session);
    S.call.timer = setInterval(() => { pollSignals().catch(() => {}); }, CALL_POLL_MS);
  } catch (error) {
    S.call = null;
    dialog.remove();
    const reason = error?.name === 'NotAllowedError'
      ? 'micro ou caméra refusé par le navigateur'
      : error?.name === 'NotFoundError'
        ? 'aucun micro détecté sur cet appareil'
        : error?.message || humanError(error);
    toast(`Appel impossible : ${reason}.`);
  }
}

async function pollSignals() {
  const state = S.call;
  if (!state) return;
  const rows = await signalingSince(state.conversationId, state.startedAt);
  for (const signal of collectSignals(rows, S.profile?.id)) {
    if (signal.callId && state.session.callId && signal.callId !== state.session.callId) continue;
    await state.session.handleSignal(signal);
  }
}

function endCall(reason = 'terminé') {
  const state = S.call;
  if (!state) return;
  S.call = null;
  clearInterval(state.timer);
  state.session.end(reason);
  const dialog = state.dialog;
  const node = dialog.querySelector('.call-status');
  if (node) node.textContent = reason === 'terminé' ? 'Appel terminé' : `Appel ${reason}`;
  const stage = dialog.querySelector('.call-stage');
  stage?.classList.add('ended');
  setTimeout(() => {
    if (dialog.open) dialog.close();
    dialog.remove();
  }, 1100);
  cleanupSignals(state.conversationId, state.startedAt).catch(() => {});
}

function stopCallWatch() {
  if (S.callWatch?.timer) clearInterval(S.callWatch.timer);
  S.callWatch = null;
}

function startCallWatch(conversationId) {
  stopCallWatch();
  if (!ONLINE() || !conversationId) return;
  const since = new Date().toISOString();
  const timer = setInterval(async () => {
    if (!S.callWatch || S.call) return;
    try {
      const rows = await signalingSince(conversationId, S.callWatch.since);
      const offer = collectSignals(rows, S.profile?.id).find((signal) => signal?.kind === 'offer');
      if (!offer) return;
      stopCallWatch();
      await incomingCall(offer, since);
    } catch {
      // Réseau absent : la surveillance reprend au prochain tick.
    }
  }, CALL_WATCH_MS);
  S.callWatch = { conversationId, since, timer };
}

function incomingCall(offer, since) {
  return new Promise((resolve) => {
    const video = Boolean(offer.video);
    const dialog = document.createElement('dialog');
    dialog.className = 'call-dialog incoming';
    dialog.innerHTML = `
      <div class="call-stage">
        <div class="call-glyph">${video ? ICON.camera : ICON.phone}</div>
        <div class="call-status">Appel ${video ? 'vidéo' : 'audio'} entrant</div>
        <div class="tiny muted">Reçu ${esc(fmtDate(new Date().toISOString()))}</div>
      </div>
      <div class="call-actions">
        <button class="call-btn" data-choice="decline">Refuser</button>
        <button class="call-btn accept" data-choice="accept">Répondre</button>
      </div>`;
    document.body.appendChild(dialog);
    dialog.showModal();
    const finish = async (accepted) => {
      dialog.close();
      dialog.remove();
      if (accepted) await answerCall(offer);
      else {
        await signalingSend(S.param, { kind: 'end', callId: offer.callId }).catch(() => {});
        cleanupSignals(S.param, since).catch(() => {});
      }
      resolve();
    };
    dialog.addEventListener('click', (event) => {
      const choice = event.target.closest('[data-choice]')?.dataset.choice;
      if (choice) finish(choice === 'accept');
    });
    dialog.addEventListener('cancel', (event) => {
      event.preventDefault();
      finish(false);
    });
  });
}
