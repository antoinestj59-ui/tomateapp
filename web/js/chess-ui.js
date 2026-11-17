// Interface du jeu d'échecs de la PWA.
//
// Modes réels :
//   - deux joueurs sur le même appareil ;
//   - contre l'IA locale (aucun réseau) ;
//   - deux appareils en connexion directe (WebRTC), sans serveur : les deux joueurs échangent
//     un code de connexion à la main (copier/coller). C'est le seul mode « deux téléphones »
//     qu'une page web peut réellement offrir : aucun navigateur ne peut découvrir un autre
//     appareil en Bluetooth (pas de mode périphérique, pas d'annonce BLE).
//
// Rien n'est simulé : chaque coup est validé par le moteur, et l'état de connexion affiché est
// celui du canal de données.

import {
  initialState, parseFen, toFen, legalMoves, applyMove, findMove, toSan, gameStatus,
  positionKey, bestMove, squareName, squareIndex, fileOf, rankOf,
} from './chess.js';

const STORAGE_KEY = 'tomate.chess.v2';
const SOLID = { P: '♟', N: '♞', B: '♝', R: '♜', Q: '♛', K: '♚' };
const glyphOf = (piece) => SOLID[piece.toUpperCase()];

const START_COUNTS = { P: 8, N: 2, B: 2, R: 2, Q: 1, K: 1, p: 8, n: 2, b: 2, r: 2, q: 1, k: 1 };

const isWhitePiece = (p) => p === p.toUpperCase();

function freshGame() {
  return {
    state: initialState(),
    san: [],
    keys: [],
    lastMove: null,
    mode: 'local',
    human: 'w',
    flipped: false,
    selected: null,
    promo: null,
    result: null,
  };
}

export function createChessUi(ctx) {
  const { S, render, toast, esc } = ctx;
  let peer = null;

  function load() {
    if (S.chess) return S.chess;
    let game = freshGame();
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const saved = JSON.parse(raw);
        if (saved?.fen) {
          game = {
            ...freshGame(),
            state: parseFen(saved.fen),
            san: Array.isArray(saved.san) ? saved.san : [],
            keys: Array.isArray(saved.keys) ? saved.keys : [],
            lastMove: saved.lastMove ?? null,
            mode: ['local', 'ai', 'peer'].includes(saved.mode) ? saved.mode : 'local',
            human: saved.human === 'b' ? 'b' : 'w',
            flipped: Boolean(saved.flipped),
          };
          game.result = gameStatus(game.state, game.keys);
        }
      }
    } catch {
      game = freshGame();
    }
    S.chess = game;
    return game;
  }

  function save(game) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        fen: toFen(game.state),
        san: game.san,
        keys: game.keys,
        lastMove: game.lastMove,
        mode: game.mode,
        human: game.human,
        flipped: game.flipped,
      }));
    } catch {
      // Le stockage local peut être refusé : la partie reste jouable en mémoire.
    }
  }

  const over = (game) => ['checkmate', 'stalemate', 'draw-material', 'draw-50', 'draw-repetition', 'resign'].includes(game.result);

  function myColor(game) {
    if (game.mode !== 'peer') return game.state.turn;
    return peer?.color ?? 'w';
  }

  function statusLine(game) {
    const turnLabel = game.state.turn === 'w' ? 'aux Blancs' : 'aux Noirs';
    switch (game.result) {
      case 'checkmate':
        return `Échec et mat — les ${game.state.turn === 'w' ? 'Noirs' : 'Blancs'} gagnent.`;
      case 'stalemate':
        return 'Pat — partie nulle.';
      case 'draw-material':
        return 'Nulle : matériel insuffisant pour mater.';
      case 'draw-50':
        return 'Nulle : 50 coups sans capture ni poussée de pion.';
      case 'draw-repetition':
        return 'Nulle : la même position s’est répétée trois fois.';
      case 'resign':
        return `${game.resignedBy === 'w' ? 'Les Blancs' : 'Les Noirs'} abandonnent — les ${game.resignedBy === 'w' ? 'Noirs' : 'Blancs'} gagnent.`;
      case 'check':
        return `Échec — ${turnLabel} de jouer.`;
      default:
        return `Aux ${game.state.turn === 'w' ? 'Blancs' : 'Noirs'} de jouer.`;
    }
  }

  function applyAndRecord(game, move) {
    const san = toSan(game.state, move);
    const next = applyMove(game.state, move);
    game.san.push(san);
    game.state = next;
    game.keys.push(positionKey(next));
    game.lastMove = { from: move.from, to: move.to };
    game.selected = null;
    game.promo = null;
    game.result = gameStatus(next, game.keys);
    save(game);
    return { san, move };
  }

  function playMove(game, from, to, promo = null, send = true) {
    if (over(game)) return false;
    const move = findMove(game.state, from, to, promo);
    if (!move) return false;
    if (game.mode === 'peer' && game.state.turn !== (peer?.color ?? 'w')) return false;
    applyAndRecord(game, move);
    if (send && game.mode === 'peer') sendPeer({ t: 'move', from, to, promo: move.promo ?? null });
    return true;
  }

  function aiTurn(game) {
    if (game.mode !== 'ai' || over(game)) return;
    if (game.state.turn === game.human) return;
    setTimeout(() => {
      const move = bestMove(game.state, 3);
      if (!move) return;
      applyAndRecord(game, move);
      render();
    }, 180);
  }

  function captured(game) {
    const value = { p: 1, n: 3, b: 3, r: 5, q: 9 };
    const counts = { ...START_COUNTS };
    let whiteValue = 0;
    let blackValue = 0;
    game.state.board.forEach((piece) => {
      if (!piece) return;
      counts[piece] = (counts[piece] ?? 0) - 1;
      const v = value[piece.toLowerCase()] ?? 0;
      if (isWhitePiece(piece)) whiteValue += v; else blackValue -= v;
    });
    const missing = [];
    for (const [piece, left] of Object.entries(counts)) {
      for (let i = 0; i < Math.max(0, left); i += 1) missing.push(piece);
    }
    const score = whiteValue - blackValue;
    return { missing, score };
  }

  // ------------------------------------------------------------------ rendu

  function boardHtml(game) {
    const squares = [];
    const order = [];
    for (let i = 0; i < 64; i += 1) order.push(game.flipped ? 63 - i : i);
    const targets = new Map();
    if (game.selected !== null) {
      legalMoves(game.state)
        .filter((m) => m.from === game.selected)
        .forEach((m) => targets.set(m.to, m));
    }
    order.forEach((idx) => {
      const dark = (fileOf(idx) + rankOf(idx)) % 2 === 1;
      const piece = game.state.board[idx];
      const classes = ['sq', dark ? 'dark' : 'light'];
      if (game.selected === idx) classes.push('sel');
      if (targets.has(idx)) classes.push(piece || targets.get(idx).ep ? 'take' : 'move');
      if (game.lastMove && (game.lastMove.from === idx || game.lastMove.to === idx)) classes.push('last');
      if (piece && piece.toUpperCase() === 'K' && piece === (game.state.turn === 'w' ? 'K' : 'k')) {
        const inCheck = game.result === 'check' || game.result === 'checkmate';
        if (inCheck) classes.push('incheck');
      }
      const fileLabel = rankOf(idx) === 7 ? `<i class="coord file">${'abcdefgh'[fileOf(idx)]}</i>` : '';
      const rankLabel = fileOf(idx) === 0 ? `<i class="coord rank">${8 - rankOf(idx)}</i>` : '';
      const glyph = piece
        ? `<span class="pc ${isWhitePiece(piece) ? 'white' : 'black'}">${glyphOf(piece)}</span>`
        : '';
      squares.push(
        `<button class="${classes.join(' ')}" data-act="chess" data-op="square" data-arg="${idx}" ` +
        `aria-label="${squareName(idx)}${piece ? ` ${piece}` : ''}">${fileLabel}${rankLabel}${glyph}</button>`,
      );
    });
    return `<div class="chess-board">${squares.join('')}</div>`;
  }

  function promotionHtml(game) {
    if (game.promo === null) return '';
    const color = game.state.turn === 'w' ? 'Q' : 'q';
    const options = ['Q', 'R', 'B', 'N'].map((p) => {
      const piece = color === 'Q' ? p : p.toLowerCase();
      return `<button class="promo-opt" data-act="chess" data-op="promo" data-arg="${p}">
        <span class="pc ${color === 'Q' ? 'white' : 'black'}">${glyphOf(piece)}</span>
        <span class="tiny">${p === 'Q' ? 'Dame' : p === 'R' ? 'Tour' : p === 'B' ? 'Fou' : 'Cavalier'}</span>
      </button>`;
    }).join('');
    return `<div class="promo"><strong>Promotion</strong><div class="promo-row">${options}</div></div>`;
  }

  function movesHtml(game) {
    if (!game.san.length) return '<p class="muted small">Aucun coup joué.</p>';
    const rows = [];
    for (let i = 0; i < game.san.length; i += 2) {
      rows.push(
        `<div class="move-row"><span class="move-no">${i / 2 + 1}.</span>` +
        `<span>${esc(game.san[i] ?? '')}</span><span>${esc(game.san[i + 1] ?? '')}</span></div>`,
      );
    }
    return `<div class="move-list">${rows.join('')}</div>`;
  }

  function capturedHtml(game) {
    const { missing, score } = captured(game);
    const white = missing.filter((p) => !isWhitePiece(p));
    const black = missing.filter((p) => isWhitePiece(p));
    if (!white.length && !black.length) return '';
    const adv = score > 0 ? `Blancs +${score}` : score < 0 ? `Noirs +${-score}` : 'Matériel égal';
    return `<div class="captured">
      <div class="cap-line"><span class="tiny muted">Pris par les Blancs</span><span class="cap-pieces">${white.map(glyphOf).join('') || '—'}</span></div>
      <div class="cap-line"><span class="tiny muted">Pris par les Noirs</span><span class="cap-pieces">${black.map(glyphOf).join('') || '—'}</span></div>
      <div class="tiny"><strong>${adv}</strong></div>
    </div>`;
  }

  function peerPanel(game) {
    if (game.mode !== 'peer') return '';
    const p = peer ?? { status: 'idle', color: 'w' };

    if (p.status === 'idle') {
      return `<div class="card chess-peer">
        <h3>Deux appareils, sans serveur</h3>
        <p class="small muted">
          Les deux navigateurs se connectent directement : le code ci-dessous sert de poignée de
          main, rien ne transite par un serveur de jeu. Même Wi-Fi ou même partage de connexion.
        </p>
        <div class="row wrap" style="gap:8px">
          <button class="btn btn-primary" data-act="chess" data-op="offer">Créer la partie</button>
          <button class="btn btn-soft" data-act="chess" data-op="join">Rejoindre avec un code</button>
        </div>
        <p class="tiny muted" style="margin-top:10px">
          Le Bluetooth n’est pas utilisé : une page web ne peut ni annoncer ni découvrir un autre
          appareil en BLE. Le jeu Bluetooth réel demande l’application Android.
        </p>
      </div>`;
    }

    if (p.status === 'offer') {
      return `<div class="card chess-peer">
        <h3>1. Envoie ce code</h3>
        <textarea class="textarea code-box" readonly rows="3" data-chess-code>${esc(p.offer ?? '')}</textarea>
        <div class="row" style="gap:8px; margin-top:8px">
          <button class="btn btn-soft" data-act="chess" data-op="copy">Copier</button>
        </div>
        <div class="divider"></div>
        <h3>2. Colle la réponse</h3>
        <textarea class="textarea" rows="3" data-chess-reply placeholder="Réponse de l’autre appareil…"></textarea>
        <button class="btn btn-primary btn-block" style="margin-top:8px" data-act="chess" data-op="connect">Connecter</button>
      </div>`;
    }

    if (p.status === 'join-input') {
      return `<div class="card chess-peer">
        <h3>Colle le code de l’hôte</h3>
        <textarea class="textarea" rows="3" data-chess-offer placeholder="Code reçu…"></textarea>
        <button class="btn btn-primary btn-block" style="margin-top:8px" data-act="chess" data-op="make-answer">Générer ma réponse</button>
      </div>`;
    }

    if (p.status === 'join-answer') {
      return `<div class="card chess-peer">
        <h3>1. Envoie cette réponse</h3>
        <textarea class="textarea code-box" readonly rows="3" data-chess-code>${esc(p.answer ?? '')}</textarea>
        <button class="btn btn-soft" style="margin-top:8px" data-act="chess" data-op="copy">Copier</button>
        <p class="small muted" style="margin-top:10px">Dès que l’hôte l’a collée, la partie démarre. Tu joues les Noirs.</p>
      </div>`;
    }

    if (p.status === 'connecting') {
      return `<div class="card chess-peer">
        <h3>Connexion…</h3>
        <p class="small muted">Les deux appareils négocient la liaison directe. Cela prend quelques secondes.</p>
      </div>`;
    }

    if (p.status === 'connected') {
      return `<div class="card chess-peer">
        <div class="row-between"><h3>Partie en direct</h3><span class="chip online">Connecté</span></div>
        <p class="small muted">Tu joues les ${p.color === 'w' ? 'Blancs' : 'Noirs'}. Chaque coup part par la liaison directe.</p>
        <button class="btn btn-soft" style="margin-top:8px" data-act="chess" data-op="peer-reset">Fermer la liaison</button>
      </div>`;
    }

    return `<div class="card chess-peer">
      <h3>Connexion impossible</h3>
      <p class="small">${esc(p.error ?? 'La liaison directe n’a pas abouti.')}</p>
      <button class="btn btn-soft" data-act="chess" data-op="peer-reset">Recommencer</button>
    </div>`;
  }

  function view() {
    const game = load();
    if (!peer && game.mode === 'peer') peer = { status: 'idle', color: 'w' };
    const modeLabel = game.mode === 'local' ? 'À deux sur cet écran' : game.mode === 'ai' ? 'Contre l’IA' : 'Deux appareils';
    return `${ctx.shell(`
      <div class="chess-hero">
        <div>
          <p class="tiny muted" style="letter-spacing:.12em; text-transform:uppercase; margin:0">TOMATE Jeux</p>
          <h1 style="margin:2px 0 4px">Échecs</h1>
          <p class="small muted" style="margin:0">${esc(modeLabel)}</p>
        </div>
        <div class="chess-badge">♞</div>
      </div>

      <div class="chess-modes">
        ${[
          ['local', '2 joueurs'],
          ['ai', 'IA'],
          ['peer', '2 appareils'],
        ].map(([id, label]) => `<button class="seg ${game.mode === id ? 'on' : ''}" data-act="chess" data-op="mode" data-arg="${id}">${label}</button>`).join('')}
      </div>

      <div class="card chess-status">
        <div class="row-between">
          <strong>${esc(statusLine(game))}</strong>
          ${game.result === 'check' ? '<span class="chip offline">Échec</span>' : ''}
        </div>
        ${game.mode === 'ai' ? `<p class="tiny muted" style="margin:6px 0 0">Tu joues les ${game.human === 'w' ? 'Blancs' : 'Noirs'}. L’IA cherche 3 demi-coups, en local.</p>` : ''}
      </div>

      ${promotionHtml(game)}
      ${boardHtml(game)}
      ${capturedHtml(game)}

      <div class="row wrap chess-controls" style="gap:8px; margin-top:12px">
        <button class="btn btn-primary" data-act="chess" data-op="new">Nouvelle partie</button>
        <button class="btn btn-soft" data-act="chess" data-op="flip">Retourner</button>
        <button class="btn btn-soft" data-act="chess" data-op="undo" ${game.mode === 'peer' ? 'disabled' : ''}>Annuler</button>
        <button class="btn btn-soft" data-act="chess" data-op="resign" ${over(game) ? 'disabled' : ''}>Abandonner</button>
      </div>

      ${peerPanel(game)}

      <div class="section-title"><h2>Coups</h2></div>
      <div class="card">${movesHtml(game)}</div>

      <p class="tiny muted" style="margin:18px 2px">
        Règles complètes : roque, prise en passant, promotion, échec et mat, pat, nulle par
        matériel insuffisant, 50 coups et répétition triple.
        <button class="link" data-act="nav" data-route="#/network" style="padding:0">Réseau et capacités</button>
      </p>
    `)}`;
  }

  // ------------------------------------------------------------------ pair à pair

  function encode(sdp) {
    return btoa(unescape(encodeURIComponent(sdp))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function decode(code) {
    const clean = code.trim().replace(/-/g, '+').replace(/_/g, '/');
    return decodeURIComponent(escape(atob(clean)));
  }

  async function gather(pc) {
    if (pc.iceGatheringState === 'complete') return;
    await new Promise((resolve) => {
      const timer = setTimeout(resolve, 2500);
      pc.addEventListener('icegatheringstatechange', () => {
        if (pc.iceGatheringState === 'complete') {
          clearTimeout(timer);
          resolve();
        }
      });
    });
  }

  function newPeer() {
    const pc = new RTCPeerConnection({ iceServers: [] });
    const entry = { pc, channel: null, color: 'b', status: 'idle' };
    pc.addEventListener('connectionstatechange', () => {
      if (pc.connectionState === 'connected') {
        entry.status = entry.channel ? 'connected' : entry.status;
        clearTimeout(entry.timer);
        render();
      }
      if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
        entry.status = 'error';
        entry.error = 'La connexion directe a échoué : les deux appareils ne se joignent pas.';
        render();
      }
    });
    return entry;
  }

  function watchdog(entry) {
    clearTimeout(entry.timer);
    entry.timer = setTimeout(() => {
      if (entry.status !== 'connected') {
        entry.status = 'error';
        entry.error = 'La liaison directe n’a pas abouti en 12 s. Vérifie que les deux appareils sont sur le même Wi-Fi, puis recommence.';
        render();
      }
    }, 12000);
  }

  function wireChannel(entry, channel) {
    entry.channel = channel;
    channel.addEventListener('open', () => {
      clearTimeout(entry.timer);
      entry.status = 'connected';
      render();
    });
    channel.addEventListener('message', (event) => {
      let data;
      try { data = JSON.parse(event.data); } catch { return; }
      const game = load();
      if (data.t === 'move') {
        if (game.state.turn === entry.color) return;
        if (playMove(game, data.from, data.to, data.promo, false)) render();
        else toast('Coup distant refusé : il n’est pas légal.');
      } else if (data.t === 'resign') {
        game.resignedBy = entry.color;
        game.result = 'resign';
        save(game);
        render();
      } else if (data.t === 'new') {
        S.chess = freshGame();
        S.chess.mode = 'peer';
        render();
      }
    });
  }

  function sendPeer(payload) {
    if (peer?.channel?.readyState === 'open') peer.channel.send(JSON.stringify(payload));
  }

  async function createOffer() {
    peer = newPeer();
    peer.color = 'w';
    const channel = peer.pc.createDataChannel('tomate-chess');
    wireChannel(peer, channel);
    const offer = await peer.pc.createOffer();
    await peer.pc.setLocalDescription(offer);
    await gather(peer.pc);
    peer.offer = encode(peer.pc.localDescription.sdp);
    peer.status = 'offer';
    S.chess.mode = 'peer';
    save(S.chess);
    render();
    watchdog(peer);
  }

  async function joinWithCode(code) {
    peer = newPeer();
    peer.color = 'b';
    try {
      await peer.pc.setRemoteDescription({ type: 'offer', sdp: decode(code) });
    } catch {
      peer.status = 'error';
      peer.error = 'Ce code n’est pas une offre valide.';
      render();
      return;
    }
    const answer = await peer.pc.createAnswer();
    await peer.pc.setLocalDescription(answer);
    await gather(peer.pc);
    peer.answer = encode(peer.pc.localDescription.sdp);
    peer.status = 'join-answer';
    peer.pc.addEventListener('datachannel', (event) => wireChannel(peer, event.channel));
    render();
    watchdog(peer);
  }

  async function connectWithAnswer(code) {
    try {
      await peer.pc.setRemoteDescription({ type: 'answer', sdp: decode(code) });
      peer.status = 'connecting';
      watchdog(peer);
    } catch {
      peer.status = 'error';
      peer.error = 'Réponse invalide : recopie le code complet.';
    }
    render();
  }

  // ------------------------------------------------------------------ actions

  async function handle(op, arg) {
    const game = load();
    switch (op) {
      case 'square': {
        if (over(game)) return;
        const idx = Number(arg);
        if (game.mode === 'peer' && game.state.turn !== (peer?.color ?? 'w')) {
          return toast('C’est à l’autre joueur de jouer.');
        }
        if (game.mode === 'ai' && game.state.turn !== game.human) {
          return toast('L’IA réfléchit…');
        }
        const piece = game.state.board[idx];
        if (game.selected !== null) {
          const pending = legalMoves(game.state).filter((m) => m.from === game.selected && m.to === idx);
          if (pending.length) {
            if (pending.some((m) => m.promo)) {
              game.promo = { from: game.selected, to: idx };
              render();
              return;
            }
            playMove(game, game.selected, idx);
            render();
            aiTurn(game);
            return;
          }
        }
        if (piece && (game.state.turn === 'w') === isWhitePiece(piece)) {
          game.selected = idx;
        } else {
          game.selected = null;
        }
        render();
        return;
      }
      case 'promo': {
        if (!game.promo) return;
        const { from, to } = game.promo;
        playMove(game, from, to, arg);
        render();
        aiTurn(game);
        return;
      }
      case 'mode': {
        game.mode = arg;
        game.selected = null;
        game.promo = null;
        if (arg === 'peer') peer = { status: 'idle', color: 'w' };
        save(game);
        render();
        aiTurn(game);
        return;
      }
      case 'new': {
        const mode = game.mode;
        S.chess = freshGame();
        S.chess.mode = mode;
        if (mode === 'ai') S.chess.human = game.human === 'w' ? 'b' : 'w';
        if (mode === 'peer') {
          S.chess.human = 'w';
          S.chess.mode = 'peer';
          if (peer?.status === 'connected') sendPeer({ t: 'new' });
        }
        save(S.chess);
        render();
        if (mode === 'ai') aiTurn(S.chess);
        return;
      }
      case 'flip':
        game.flipped = !game.flipped;
        save(game);
        render();
        return;
      case 'undo': {
        if (game.mode === 'peer') return;
        // On remonte d'un coup, ou de deux contre l'IA pour rendre la main au joueur.
        const back = game.mode === 'ai' ? 2 : 1;
        if (!game.san.length) return;
        const keep = Math.max(0, game.san.length - back);
        const replay = freshGame();
        for (const san of game.san.slice(0, keep)) {
          const move = legalMoves(replay.state).find((m) => toSan(replay.state, m) === san);
          if (!move) break;
          applyAndRecord(replay, move);
        }
        S.chess = { ...replay, mode: game.mode, human: game.human, flipped: game.flipped };
        save(S.chess);
        render();
        return;
      }
      case 'resign': {
        if (over(game)) return;
        game.resignedBy = game.state.turn;
        game.result = 'resign';
        if (game.mode === 'peer') sendPeer({ t: 'resign' });
        save(game);
        render();
        return;
      }
      case 'offer':
        return createOffer();
      case 'join':
        peer = { status: 'join-input', color: 'b' };
        render();
        return;
      case 'make-answer': {
        const offer = document.querySelector('[data-chess-offer]')?.value?.trim();
        if (!offer) return toast('Colle d’abord le code de l’hôte.');
        return joinWithCode(offer);
      }
      case 'connect': {
        const text = document.querySelector('[data-chess-reply]')?.value?.trim();
        if (!text) return toast('Colle la réponse de ton adversaire.');
        return connectWithAnswer(text);
      }
      case 'copy': {
        const box = document.querySelector('[data-chess-code]');
        if (!box) return;
        try {
          await navigator.clipboard.writeText(box.value);
          toast('Code copié.');
        } catch {
          box.select();
          toast('Copie manuelle : le code est sélectionné.');
        }
        return;
      }
      case 'peer-reset':
        if (peer?.pc) peer.pc.close();
        peer = { status: 'idle', color: 'w' };
        render();
        return;
      default:
        return;
    }
  }

  return { view, handle };
}
