// TOMATE — extensions de conversation : sondages, événements, appels WebRTC.
//
// Aucune de ces fonctions n'a besoin d'une nouvelle table : elles réutilisent `messages`,
// `posts` et `post_reactions`, qui existent depuis la 0001 et dont les politiques RLS ont
// déjà été écrites pour la messagerie de groupe et les publications de communauté.
//
// Trois protocoles explicites, préfixés et versionnés. Le préfixe permet à un ancien client
// d'afficher du texte brut au lieu de casser, et le numéro de version permet de changer le
// format plus tard sans relire les anciens messages de travers.
//
//   [[tomate:poll:v1]]{"q":"…","o":["…","…"]}      question + options
//   [[tomate:vote:v1]]<message_id>:<option_index>   un vote, une ligne `messages`
//   [[tomate:event:v1]]{"t":"…","at":"ISO","p":"…"} l'événement est une publication
//   [[tomate:call:v1]]{"kind":"offer",…}            signalisation d'appel, éphémère
//
// Les votes et la signalisation sont filtrés à l'affichage : ils existent en base pour être
// partagés entre appareils, mais ne s'affichent jamais comme des messages.

export const POLL_PREFIX = '[[tomate:poll:v1]]';
export const VOTE_PREFIX = '[[tomate:vote:v1]]';
export const EVENT_PREFIX = '[[tomate:event:v1]]';
export const CALL_PREFIX = '[[tomate:call:v1]]';


function parsePayload(prefix, body) {
  if (typeof body !== 'string' || !body.startsWith(prefix)) return null;
  try {
    const parsed = JSON.parse(body.slice(prefix.length));
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------- sondages

export function encodePoll({ question, options }) {
  return POLL_PREFIX + JSON.stringify({ q: question, o: options });
}

export function decodePoll(body) {
  const payload = parsePayload(POLL_PREFIX, body);
  if (!payload || typeof payload.q !== 'string' || !Array.isArray(payload.o)) return null;
  const options = payload.o.filter((o) => typeof o === 'string' && o.trim()).slice(0, 6);
  if (!options.length) return null;
  return { question: payload.q, options };
}

export function encodeVote(pollId, index) {
  return VOTE_PREFIX + `${pollId}:${index}`;
}

export function isVote(body) {
  return typeof body === 'string' && body.startsWith(VOTE_PREFIX);
}

export function decodeVote(body) {
  if (!isVote(body)) return null;
  const raw = body.slice(VOTE_PREFIX.length);
  const index = raw.lastIndexOf(':');
  if (index < 0) return null;
  const pollId = raw.slice(0, index);
  const option = Number.parseInt(raw.slice(index + 1), 10);
  if (!pollId || !Number.isInteger(option) || option < 0) return null;
  return { pollId, option };
}

/**
 * Dépouillement local et déterministe : chaque votant ne compte qu'une fois, son vote le plus
 * récent l'emporte. Les votes sont triés par date avant lecture, donc deux appareils qui ont
 * reçu les mêmes lignes affichent le même résultat.
 */
export function tallyPoll(messages, pollId, meId) {
  const votes = new Map();
  for (const message of messages) {
    if (!isVote(message.body)) continue;
    const vote = decodeVote(message.body);
    if (!vote || vote.pollId !== pollId) continue;
    const previous = votes.get(message.sender_id);
    if (previous && previous.at > (message.created_at ?? '')) continue;
    votes.set(message.sender_id, { option: vote.option, at: message.created_at ?? '' });
  }
  const counts = new Map();
  for (const { option } of votes.values()) counts.set(option, (counts.get(option) ?? 0) + 1);
  return {
    counts,
    total: votes.size,
    mine: votes.get(meId)?.option ?? null,
  };
}

// ------------------------------------------------------------------ événements

export function encodeEvent({ title, startsAt, place, details }) {
  return EVENT_PREFIX + JSON.stringify({
    t: title,
    at: startsAt,
    p: place ?? '',
    d: details ?? '',
  });
}

export function decodeEvent(body) {
  const payload = parsePayload(EVENT_PREFIX, body);
  if (!payload || typeof payload.t !== 'string' || typeof payload.at !== 'string') return null;
  const date = new Date(payload.at);
  if (Number.isNaN(date.getTime())) return null;
  return {
    title: payload.t,
    startsAt: payload.at,
    place: typeof payload.p === 'string' ? payload.p : '',
    details: typeof payload.d === 'string' ? payload.d : '',
  };
}

export function formatEventDate(iso) {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const day = date.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
  const time = date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  return `${day} à ${time}`;
}

/** Une date ISO sans fuseau est interprétée comme heure locale, pas comme UTC. */
export function localInputToIso(value) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

// ---------------------------------------------------------------------- appels

export const CALL_ICE = [
  { urls: 'stun:stun.l.google.com:19302' },
  { urls: 'stun:stun1.l.google.com:19302' },
];

// `messages.body` est limité à 4000 caractères (contrainte de la 0001) alors qu'un SDP pèse
// facilement 3 à 6 Ko : la signalisation est donc découpée en morceaux numérotés, réassemblés
// à la lecture. Chaque morceau reste un message ordinaire, donc soumis aux mêmes règles RLS.
const CALL_CHUNK = 3000;

export function packSignal(signal) {
  const payload = btoa(unescape(encodeURIComponent(JSON.stringify(signal))));
  const id = crypto.randomUUID();
  const total = Math.max(1, Math.ceil(payload.length / CALL_CHUNK));
  return Array.from({ length: total }, (_, index) =>
    `${CALL_PREFIX}${id}|${index}|${total}|${payload.slice(index * CALL_CHUNK, (index + 1) * CALL_CHUNK)}`);
}

/** Réassemble les morceaux complets reçus d'un autre participant, dans l'ordre. */
export function collectSignals(messages, meId) {
  const packs = new Map();
  for (const message of messages) {
    if (!message?.body?.startsWith(CALL_PREFIX) || message.sender_id === meId) continue;
    const raw = message.body.slice(CALL_PREFIX.length);
    const [id, indexRaw, totalRaw, chunk] = raw.split('|');
    const index = Number.parseInt(indexRaw, 10);
    const total = Number.parseInt(totalRaw, 10);
    if (!id || !Number.isInteger(index) || !Number.isInteger(total) || typeof chunk !== 'string') continue;
    const pack = packs.get(id) ?? { total, parts: new Map(), at: message.created_at };
    pack.parts.set(index, chunk);
    packs.set(id, pack);
  }
  const signals = [];
  for (const pack of packs.values()) {
    if (pack.parts.size !== pack.total) continue;
    let payload = '';
    for (let index = 0; index < pack.total; index += 1) payload += pack.parts.get(index) ?? '';
    try {
      signals.push({ ...JSON.parse(decodeURIComponent(escape(atob(payload)))), at: pack.at });
    } catch {
      // Morceau illisible : ignoré plutôt que d'interrompre l'appel.
    }
  }
  return signals;
}

export function decodeCall(body) {
  return parsePayload(CALL_PREFIX, body);
}

/**
 * Une session d'appel 1-à-1. Le transport est injecté : l'application envoie la signalisation
 * par les messages de la conversation, les tests utilisent un transport en mémoire. La classe
 * ne connaît ni Supabase ni le DOM, ce qui la rend vérifiable sans caméra ni second appareil.
 */
export class CallSession {
  constructor({ transport, video = false, media = true, localStream = null, onRemote, onState, iceServers = CALL_ICE }) {
    this.transport = transport;
    this.video = video;
    this.media = media;
    this.providedStream = localStream;
    this.onRemote = onRemote ?? (() => {});
    this.onState = onState ?? (() => {});
    this.iceServers = iceServers;
    this.pc = null;
    this.localStream = null;
    this.callId = null;
    this.closed = false;
    this.pendingCandidates = [];
  }

  async start(callId) {
    this.callId = callId ?? crypto.randomUUID();
    await this.openPeer();
    this.probe = this.pc.createDataChannel('tomate-probe');
    const offer = await this.pc.createOffer();
    await this.pc.setLocalDescription(offer);
    await this.transport.send({ kind: 'offer', callId: this.callId, video: this.video, sdp: this.pc.localDescription.sdp });
    return this.callId;
  }

  async openPeer() {
    if (this.pc) return;
    if (this.providedStream) {
      // Flux fourni par un test : même chemin de code qu'un vrai micro, sans périphérique.
      this.localStream = this.providedStream;
    } else if (this.media) {
      this.localStream = await navigator.mediaDevices.getUserMedia({
        audio: true,
        video: this.video ? { facingMode: 'user' } : false,
      });
    }
    const pc = new RTCPeerConnection({ iceServers: this.iceServers });
    this.pc = pc;
    this.onState('connexion');
    if (this.localStream) {
      for (const track of this.localStream.getTracks()) pc.addTrack(track, this.localStream);
    }
    pc.ontrack = (event) => {
      const [stream] = event.streams;
      if (stream) this.onRemote(stream);
    };
    pc.ondatachannel = () => {
      // Le canal de sonde n'est utile qu'à la négociation : il prouve que la liaison passe.
    };
    pc.onconnectionstatechange = () => {
      const state = pc.connectionState;
      if (state === 'connected') this.onState('connecté');
      else if (state === 'failed') {
        this.onState('échoué');
        this.end('échoué');
      } else if (state === 'disconnected') this.onState('interrompu');
    };
    pc.onicecandidate = (event) => {
      if (event.candidate) {
        this.transport.send({ kind: 'ice', callId: this.callId, candidate: event.candidate.toJSON() }).catch(() => {});
      }
    };
  }

  async handleSignal(signal) {
    if (!signal || this.closed) return;
    if (signal.callId && this.callId && signal.callId !== this.callId) return;
    if (signal.kind === 'offer') {
      if (!this.callId) this.callId = signal.callId;
      if (typeof signal.video === 'boolean') this.video = signal.video;
      await this.openPeer();
      await this.pc.setRemoteDescription({ type: 'offer', sdp: signal.sdp });
      for (const candidate of this.pendingCandidates.splice(0)) {
        await this.pc.addIceCandidate(candidate).catch(() => {});
      }
      const answer = await this.pc.createAnswer();
      await this.pc.setLocalDescription(answer);
      await this.transport.send({ kind: 'answer', callId: this.callId, sdp: this.pc.localDescription.sdp });
      this.onState('sonnerie');
    } else if (signal.kind === 'answer') {
      await this.pc?.setRemoteDescription({ type: 'answer', sdp: signal.sdp });
      for (const candidate of this.pendingCandidates.splice(0)) {
        await this.pc.addIceCandidate(candidate).catch(() => {});
      }
    } else if (signal.kind === 'ice' && signal.candidate) {
      if (this.pc?.remoteDescription) await this.pc.addIceCandidate(signal.candidate).catch(() => {});
      else this.pendingCandidates.push(signal.candidate);
    } else if (signal.kind === 'end') {
      this.end('terminé');
    }
  }

  setMuted(muted) {
    this.localStream?.getAudioTracks().forEach((track) => {
      track.enabled = !muted;
    });
  }

  setCameraOff(off) {
    this.localStream?.getVideoTracks().forEach((track) => {
      track.enabled = !off;
    });
  }

  end(reason = 'terminé') {
    if (this.closed) return;
    this.closed = true;
    this.transport.send({ kind: 'end', callId: this.callId }).catch(() => {});
    this.localStream?.getTracks().forEach((track) => track.stop());
    this.pc?.close();
    this.onState(reason);
  }
}

/**
 * Auto-test de négociation : deux pairs, la même classe, un transport en mémoire.
 * Avec `media: true` il faut fournir deux flux (`streams: [a, b]`) : le test vérifie alors aussi
 * que les pistes distantes arrivent réellement. Ce n'est pas un test d'appel entre appareils :
 * les flux peuvent être synthétiques (canvas + oscillateur) quand il n'y a ni micro ni caméra.
 */
export async function loopbackNegotiationTest({ timeoutMs = 12000, media = false, streams = null } = {}) {
  const queueA = [];
  const queueB = [];
  const transportA = { send: async (signal) => { queueB.push(signal); } };
  const transportB = { send: async (signal) => { queueA.push(signal); } };

  const remote = { a: null, b: null };
  const a = new CallSession({
    transport: transportA,
    media,
    localStream: streams?.[0] ?? null,
    onRemote: (stream) => { remote.a = stream; },
    onState: () => {},
  });
  const b = new CallSession({
    transport: transportB,
    media,
    localStream: streams?.[1] ?? null,
    onRemote: (stream) => { remote.b = stream; },
    onState: () => {},
  });

  const started = Date.now();
  await a.start('loopback');

  while (Date.now() - started < timeoutMs) {
    while (queueB.length || queueA.length) {
      for (const signal of queueB.splice(0)) await b.handleSignal(signal);
      for (const signal of queueA.splice(0)) await a.handleSignal(signal);
      await new Promise((resolve) => setTimeout(resolve, 30));
    }
    if (a.pc?.connectionState === 'connected' && b.pc?.connectionState === 'connected') {
      const tracks = {
        a: (remote.a?.getTracks() ?? []).map((track) => `${track.kind}:${track.readyState}`),
        b: (remote.b?.getTracks() ?? []).map((track) => `${track.kind}:${track.readyState}`),
      };
      a.end('test');
      b.end('test');
      return { ok: !media || (tracks.a.length > 0 && tracks.b.length > 0), ms: Date.now() - started, media, tracks };
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  const states = { a: a.pc?.connectionState, b: b.pc?.connectionState };
  a.end('test');
  b.end('test');
  return { ok: false, ms: Date.now() - started, states };
}
