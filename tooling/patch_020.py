#!/usr/bin/env python3
"""Câble les sondages, événements et appels dans app.js (idempotent : vérifie les ancres)."""
import pathlib
import sys

p = pathlib.Path("web/js/app.js")
s = p.read_text()
if "from './extras.js'" in s:
    print("déjà patché")
    sys.exit(0)

replacements = []

# 1. imports
replacements.append((
    "import {\n  APP_VERSION,",
    "import {\n  CALL_PREFIX,\n  CallSession,\n  decodeCall,\n  decodeEvent,\n  decodePoll,\n  encodeCall,\n  encodeEvent,\n  encodePoll,\n  encodeVote,\n  formatEventDate,\n  isVote,\n  localInputToIso,\n  tallyPoll,\n} from './extras.js';\nimport {\n  APP_VERSION,",
))

# 2. état
replacements.append((
    "  authEmail: '',\n};",
    "  authEmail: '',\n  rsvps: {},\n  call: null,\n  callWatch: null,\n};",
))

# 3. icônes
replacements.append((
    "  bell: svg('<path d=\"M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8\"/><path d=\"M13.7 21a2 2 0 0 1-3.4 0\"/>'),\n};",
    "  bell: svg('<path d=\"M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8\"/><path d=\"M13.7 21a2 2 0 0 1-3.4 0\"/>'),\n  chart: svg('<path d=\"M4 20V10M10 20V4M16 20v-7M22 20H2\"/>'),\n  phone: svg('<path d=\"M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 2 .7 2.9a2 2 0 0 1-.5 2.1L8.1 9.9a16 16 0 0 0 6 6l1.2-1.2a2 2 0 0 1 2.1-.5c.9.3 1.9.6 2.9.7a2 2 0 0 1 1.7 2Z\"/>'),\n  camera: svg('<rect x=\"2\" y=\"6\" width=\"14\" height=\"12\" rx=\"3\"/><path d=\"m16 11 6-3.5v9L16 13Z\"/>'),\n};",
))

# 4. messages visibles (votes et signalisation masqués)
replacements.append((
    "      ${S.messages.length === 0 ? emptyState('💬', 'Aucun message.', 'Écrivez le premier message.') : S.messages.map(messageItem).join('')}",
    "      ${visibleMessages().length === 0 ? emptyState('💬', 'Aucun message.', 'Écrivez le premier message.') : visibleMessages().map(messageItem).join('')}",
))

# 5. bouton sondage dans le composeur
replacements.append((
    "      <button class=\"icon-btn\" type=\"button\" data-act=\"chat-attach\" style=\"flex:none\" aria-label=\"Joindre une photo, une vidéo ou un message vocal\">${ICON.paperclip}</button>",
    "      <button class=\"icon-btn\" type=\"button\" data-act=\"poll-new\" style=\"flex:none\" aria-label=\"Créer un sondage\">${ICON.chart}</button>\n      <button class=\"icon-btn\" type=\"button\" data-act=\"chat-attach\" style=\"flex:none\" aria-label=\"Joindre une photo, une vidéo ou un message vocal\">${ICON.paperclip}</button>",
))

# 6. appels dans la barre de titre de la conversation
replacements.append((
    "  `, { title: `${conversation?.title || 'Conversation'}${S.streaks[S.param] ? ` · 🔥 ${S.streaks[S.param]}` : ''}`, back: '#/messages' });",
    "  `, {\n    title: `${conversation?.title || 'Conversation'}${S.streaks[S.param] ? ` · 🔥 ${S.streaks[S.param]}` : ''}`,\n    back: '#/messages',\n    actions: `${conversation ? `<button class=\"icon-btn\" data-act=\"call-audio\" aria-label=\"Appeler en audio\">${ICON.phone}</button><button class=\"icon-btn\" data-act=\"call-video\" aria-label=\"Appeler en vidéo\">${ICON.camera}</button>` : ''}`,\n  });",
))

# 7. messageItem : un sondage est rendu comme un sondage
replacements.append((
    "function messageItem(message) {\n  const mine = message.sender_id === S.profile?.id;",
    "function messageItem(message) {\n  const poll = decodePoll(message.body);\n  if (poll) return pollBubble(message, poll);\n  const mine = message.sender_id === S.profile?.id;",
))

# 8. postItem : un événement est rendu comme un événement
replacements.append((
    "      ${post.body ? `<div class=\"post-body\" data-body-post=\"${esc(post.id)}\"></div>` : ''}",
    "      ${event ? eventCard(post, event) : post.body ? `<div class=\"post-body\" data-body-post=\"${esc(post.id)}\"></div>` : ''}",
))
replacements.append((
    "function postItem(post) {\n  const mine = post.author_id === S.profile?.id;",
    "function postItem(post) {\n  const event = decodeEvent(post.body);\n  const mine = post.author_id === S.profile?.id;",
))
replacements.append((
    "      <div class=\"post-actions\">\n        <button class=\"action ${post.reacted_by_me ? 'liked' : ''}\" data-act=\"like\" data-id=\"${esc(post.id)}\" aria-pressed=\"${!!post.reacted_by_me}\">\n          ${ICON.heart}<span>${post.reaction_count ?? 0}</span>\n        </button>",
    "      <div class=\"post-actions\">\n        ${event ? '' : `<button class=\"action ${post.reacted_by_me ? 'liked' : ''}\" data-act=\"like\" data-id=\"${esc(post.id)}\" aria-pressed=\"${!!post.reacted_by_me}\">${ICON.heart}<span>${post.reaction_count ?? 0}</span></button>`}",
))

# 9. feed : compter les participations des événements
replacements.append((
    "    S.feed = merged;\n    await cacheSet('feed', merged);",
    "    S.feed = merged;\n    await cacheSet('feed', merged);\n    await loadRsvps(merged);",
))

# 10. bouton « Événement » dans un groupe
replacements.append((
    "            <div class=\"row-between\">\n              <span class=\"tiny\" data-counter=\"community\">0/${MAX_POST_LENGTH}</span>\n              <button class=\"btn btn-primary\" type=\"submit\">Publier</button>\n            </div>",
    "            <div class=\"row-between\">\n              <div class=\"row\" style=\"gap:8px\">\n                <span class=\"tiny\" data-counter=\"community\">0/${MAX_POST_LENGTH}</span>\n                <button class=\"btn btn-soft btn-sm\" type=\"button\" data-act=\"event-new\" data-id=\"${esc(community.id)}\">Événement</button>\n              </div>\n              <button class=\"btn btn-primary\" type=\"submit\">Publier</button>\n            </div>",
))

# 11. actions
replacements.append((
    "      case 'qr-add':",
    "      case 'poll-new': {\n        const poll = await pollDialog();\n        if (poll) await sendMessage(S.param, encodePoll(poll), null);\n        break;\n      }\n      case 'poll-vote': {\n        if (!isVote(S.messages.length ? '' : '')) { /* garde de lisibilité */ }\n        await sendMessage(S.param, encodeVote(trigger.dataset.id, Number(trigger.dataset.index)), null);\n        break;\n      }\n      case 'event-new':\n        await createEvent(trigger.dataset.id);\n        break;\n      case 'event-rsvp': {\n        const post = S.feed.find((p) => p.id === trigger.dataset.id);\n        if (post) await toggleRsvp(post);\n        break;\n      }\n      case 'call-audio':\n        await startCall({ video: false });\n        break;\n      case 'call-video':\n        await startCall({ video: true });\n        break;\n      case 'call-hangup':\n        endCall('terminé');\n        break;\n      case 'call-mute': {\n        const state = S.call;\n        if (!state) break;\n        state.muted = !state.muted;\n        state.session.setMuted(state.muted);\n        trigger.setAttribute('aria-pressed', String(state.muted));\n        trigger.textContent = state.muted ? 'Micro coupé' : 'Micro';\n        break;\n      }\n      case 'call-camera': {\n        const state = S.call;\n        if (!state) break;\n        state.cameraOff = !state.cameraOff;\n        state.session.setCameraOff(state.cameraOff);\n        trigger.setAttribute('aria-pressed', String(state.cameraOff));\n        trigger.textContent = state.cameraOff ? 'Caméra coupée' : 'Caméra';\n        break;\n      }\n      case 'qr-add':",
))

# 12. surveillance des appels entrants quand on quitte / entre dans une conversation
replacements.append((
    "async function navigate() {\n  const { route, param } = parseRoute();\n  S.route = route;\n  S.param = param;",
    "async function navigate() {\n  const { route, param } = parseRoute();\n  if (route !== 'chat') stopCallWatch();\n  S.route = route;\n  S.param = param;",
))
replacements.append((
    "      await loadConversations({ refresh: true });\n      await loadMessages(S.param);\n      await loadStreaks();\n      render();\n      break;",
    "      await loadConversations({ refresh: true });\n      await loadMessages(S.param);\n      await loadStreaks();\n      render();\n      startCallWatch(S.param);\n      break;",
))

for old, new in replacements:
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"ancre introuvable ou ambiguë ({count}) :\n{old[:160]}")
    s = s.replace(old, new)

# 13. postView : même rendu d'événement, et pas d'hydratation de texte brut
old_view = "          <div class=\"post-body\" data-body-post=\"${esc(post.id)}\"></div>"
new_view = "          ${decodeEvent(post.body) ? eventCard(post, decodeEvent(post.body)) : `<div class=\"post-body\" data-body-post=\"${esc(post.id)}\"></div>`}"
if s.count(old_view) != 1:
    raise SystemExit(f"postView : {s.count(old_view)} occurrences")
s = s.replace(old_view, new_view)

s += r'''

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
  const community = S.communities.find((c) => c.id === communityId);
  const post = {
    id: crypto.randomUUID(),
    author_id: S.profile.id,
    author_handle: S.profile.handle,
    author_display_name: S.profile.display_name,
    body: encodeEvent(event),
    community_id: communityId,
    community_name: community?.name ?? null,
    visibility: 'Community',
    created_at: new Date().toISOString(),
    reaction_count: 0,
    comment_count: 0,
    reacted_by_me: false,
    media_path: null,
    media_type: null,
    pending: true,
  };
  S.feed = [post, ...S.feed];
  S.rsvps[post.id] = { count: 0, mine: true };
  await cacheSet('feed', S.feed);
  await enqueue('post_insert', {
    id: post.id,
    author_id: post.author_id,
    body: post.body,
    visibility: 'Community',
    community_id: communityId,
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
  await rest('messages', {
    method: 'POST',
    prefer: 'return=minimal',
    body: {
      id: crypto.randomUUID(),
      client_id: crypto.randomUUID(),
      conversation_id: conversationId,
      sender_id: session.userId,
      body: encodeCall(signal),
      encrypted: false,
    },
  });
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
  for (const row of rows) {
    if (row.sender_id === S.profile?.id) continue;
    const signal = decodeCall(row.body);
    if (!signal) continue;
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
      const offer = rows
        .filter((row) => row.sender_id !== S.profile?.id)
        .map((row) => decodeCall(row.body))
        .find((signal) => signal?.kind === 'offer');
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
'''
p.write_text(s)
print("app.js patché :", len(s.splitlines()), "lignes")
