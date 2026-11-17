// Moteur d'échecs complet, sans dépendance.
//
// Règles prises en charge : déplacements de toutes les pièces, roque (petit et grand, avec les
// conditions réelles), prise en passant, promotion, échec, échec et mat, pat, matériel
// insuffisant, règle des 50 coups, répétition triple, notation SAN avec désambiguïsation.
//
// Convention de plateau : tableau de 64 cases, index 0 = a8, index 63 = h1.
// Pièces blanches en majuscules (PNBRQK), noires en minuscules (pnbrqk).
//
// Le module est utilisé par la PWA (interface) et par `tooling/chess_test.mjs` (perft).

export const FILES = 'abcdefgh';
export const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

export const GLYPH = {
  P: '♙', N: '♘', B: '♗', R: '♖', Q: '♕', K: '♔',
  p: '♟', n: '♞', b: '♝', r: '♜', q: '♛', k: '♚',
};

const KNIGHT_DELTAS = [
  [1, 2], [2, 1], [2, -1], [1, -2],
  [-1, -2], [-2, -1], [-2, 1], [-1, 2],
];
const BISHOP_DIRS = [[1, 1], [1, -1], [-1, 1], [-1, -1]];
const ROOK_DIRS = [[1, 0], [-1, 0], [0, 1], [0, -1]];
const KING_DELTAS = [...BISHOP_DIRS, ...ROOK_DIRS];

export const fileOf = (sq) => sq % 8;
export const rankOf = (sq) => Math.floor(sq / 8); // 0 = 8e rangée
export const squareName = (sq) => FILES[fileOf(sq)] + (8 - rankOf(sq));
export const squareIndex = (name) => {
  const file = FILES.indexOf(name[0]);
  const rank = Number(name[1]);
  if (file < 0 || !(rank >= 1 && rank <= 8)) return -1;
  return (8 - rank) * 8 + file;
};
const isWhite = (piece) => piece === piece.toUpperCase();
const colorOf = (piece) => (isWhite(piece) ? 'w' : 'b');
const inside = (file, rank) => file >= 0 && file < 8 && rank >= 0 && rank < 8;
const at = (file, rank) => rank * 8 + file;

export function parseFen(fen = START_FEN) {
  const [placement, turn = 'w', castling = '-', ep = '-', half = '0', full = '1'] = fen.trim().split(/\s+/);
  const board = new Array(64).fill(null);
  let sq = 0;
  for (const ch of placement) {
    if (ch === '/') continue;
    if (/\d/.test(ch)) {
      sq += Number(ch);
    } else {
      board[sq] = ch;
      sq += 1;
    }
  }
  return {
    board,
    turn: turn === 'b' ? 'b' : 'w',
    castling: castling === '-' ? '' : castling,
    ep: ep && ep !== '-' ? squareIndex(ep) : null,
    half: Number(half) || 0,
    full: Number(full) || 1,
  };
}

export const initialState = () => parseFen(START_FEN);

export function toFen(state) {
  let placement = '';
  for (let rank = 0; rank < 8; rank += 1) {
    let empty = 0;
    for (let file = 0; file < 8; file += 1) {
      const piece = state.board[at(file, rank)];
      if (piece) {
        if (empty) { placement += empty; empty = 0; }
        placement += piece;
      } else {
        empty += 1;
      }
    }
    if (empty) placement += empty;
    if (rank < 7) placement += '/';
  }
  const castling = state.castling || '-';
  const ep = state.ep === null || state.ep === undefined ? '-' : squareName(state.ep);
  return `${placement} ${state.turn} ${castling} ${ep} ${state.half} ${state.full}`;
}

/** Clé de position pour la répétition triple (les compteurs de coups sont exclus). */
export const positionKey = (state) =>
  `${state.board.map((p) => p ?? '.').join('')}|${state.turn}|${state.castling || '-'}|${state.ep ?? '-'}`;

/** Une case est-elle attaquée par `byColor` ? */
export function isAttacked(board, sq, byColor) {
  const file = fileOf(sq);
  const rank = rankOf(sq);
  const enemyPawn = byColor === 'w' ? 'P' : 'p';
  const pawnRank = byColor === 'w' ? rank + 1 : rank - 1;
  for (const df of [-1, 1]) {
    if (inside(file + df, pawnRank) && board[at(file + df, pawnRank)] === enemyPawn) return true;
  }
  const enemyKnight = byColor === 'w' ? 'N' : 'n';
  for (const [df, dr] of KNIGHT_DELTAS) {
    if (inside(file + df, rank + dr) && board[at(file + df, rank + dr)] === enemyKnight) return true;
  }
  const enemyKing = byColor === 'w' ? 'K' : 'k';
  for (const [df, dr] of KING_DELTAS) {
    if (inside(file + df, rank + dr) && board[at(file + df, rank + dr)] === enemyKing) return true;
  }
  const bishop = byColor === 'w' ? 'B' : 'b';
  const rook = byColor === 'w' ? 'R' : 'r';
  const queen = byColor === 'w' ? 'Q' : 'q';
  for (const [df, dr] of BISHOP_DIRS) {
    let f = file + df;
    let r = rank + dr;
    while (inside(f, r)) {
      const piece = board[at(f, r)];
      if (piece) {
        if (piece === bishop || piece === queen) return true;
        break;
      }
      f += df; r += dr;
    }
  }
  for (const [df, dr] of ROOK_DIRS) {
    let f = file + df;
    let r = rank + dr;
    while (inside(f, r)) {
      const piece = board[at(f, r)];
      if (piece) {
        if (piece === rook || piece === queen) return true;
        break;
      }
      f += df; r += dr;
    }
  }
  return false;
}

export function kingSquare(board, color) {
  const king = color === 'w' ? 'K' : 'k';
  return board.indexOf(king);
}

export function isInCheck(state, color = state.turn) {
  const sq = kingSquare(state.board, color);
  if (sq < 0) return false;
  return isAttacked(state.board, sq, color === 'w' ? 'b' : 'w');
}

function addPawnMoves(moves, board, from, color, ep) {
  const dir = color === 'w' ? -8 : 8;
  const startRank = color === 'w' ? 6 : 1;
  const promoRank = color === 'w' ? 0 : 7;
  const file = fileOf(from);
  const one = from + dir;
  if (one >= 0 && one < 64 && !board[one]) {
    if (rankOf(one) === promoRank) {
      for (const promo of ['Q', 'R', 'B', 'N']) {
        moves.push({ from, to: one, piece: color === 'w' ? 'P' : 'p', promo: color === 'w' ? promo : promo.toLowerCase() });
      }
    } else {
      moves.push({ from, to: one, piece: color === 'w' ? 'P' : 'p' });
      const two = one + dir;
      if (rankOf(from) === startRank && !board[two]) {
        moves.push({ from, to: two, piece: color === 'w' ? 'P' : 'p', double: true });
      }
    }
  }
  for (const df of [-1, 1]) {
    const f = file + df;
    const to = at(f, rankOf(one));
    if (!inside(f, rankOf(one))) continue;
    const target = board[to];
    const isEnemy = target && colorOf(target) !== color;
    const isEp = target === null && ep !== null && ep !== undefined && to === ep;
    if (isEnemy || isEp) {
      if (rankOf(to) === promoRank) {
        for (const promo of ['Q', 'R', 'B', 'N']) {
          moves.push({
            from, to, piece: color === 'w' ? 'P' : 'p',
            captured: target ?? undefined, ep: isEp,
            promo: color === 'w' ? promo : promo.toLowerCase(),
          });
        }
      } else {
        moves.push({
          from, to, piece: color === 'w' ? 'P' : 'p',
          captured: target ?? undefined, ep: isEp,
        });
      }
    }
  }
}

function addStepMoves(moves, board, from, color, deltas, pieces) {
  const file = fileOf(from);
  const rank = rankOf(from);
  for (const [df, dr] of deltas) {
    if (!inside(file + df, rank + dr)) continue;
    const to = at(file + df, rank + dr);
    const target = board[to];
    if (target && colorOf(target) === color) continue;
    for (const piece of pieces) {
      moves.push({ from, to, piece, captured: target ?? undefined });
    }
  }
}

function addSlidingMoves(moves, board, from, color, dirs, pieces) {
  const file = fileOf(from);
  const rank = rankOf(from);
  for (const [df, dr] of dirs) {
    let f = file + df;
    let r = rank + dr;
    while (inside(f, r)) {
      const to = at(f, r);
      const target = board[to];
      if (target && colorOf(target) === color) break;
      for (const piece of pieces) {
        moves.push({ from, to, piece, captured: target ?? undefined });
      }
      if (target) break;
      f += df; r += dr;
    }
  }
}

function addCastling(state, moves, color) {
  const board = state.board;
  const enemy = color === 'w' ? 'b' : 'w';
  const homeRank = color === 'w' ? 7 : 0;
  const kingFrom = at(4, homeRank);
  const king = color === 'w' ? 'K' : 'k';
  if (board[kingFrom] !== king) return;
  if (isAttacked(board, kingFrom, enemy)) return;

  const kingSide = color === 'w' ? 'K' : 'k';
  const queenSide = color === 'w' ? 'Q' : 'q';
  if (state.castling.includes(kingSide)) {
    const rookFrom = at(7, homeRank);
    const rook = color === 'w' ? 'R' : 'r';
    if (
      board[rookFrom] === rook &&
      !board[at(5, homeRank)] && !board[at(6, homeRank)] &&
      !isAttacked(board, at(5, homeRank), enemy) &&
      !isAttacked(board, at(6, homeRank), enemy)
    ) {
      moves.push({ from: kingFrom, to: at(6, homeRank), piece: king, castle: 'K' });
    }
  }
  if (state.castling.includes(queenSide)) {
    const rookFrom = at(0, homeRank);
    const rook = color === 'w' ? 'R' : 'r';
    if (
      board[rookFrom] === rook &&
      !board[at(1, homeRank)] && !board[at(2, homeRank)] && !board[at(3, homeRank)] &&
      !isAttacked(board, at(3, homeRank), enemy) &&
      !isAttacked(board, at(2, homeRank), enemy)
    ) {
      moves.push({ from: kingFrom, to: at(2, homeRank), piece: king, castle: 'Q' });
    }
  }
}

function pseudoMoves(state) {
  const moves = [];
  const board = state.board;
  for (let from = 0; from < 64; from += 1) {
    const piece = board[from];
    if (!piece) continue;
    const color = colorOf(piece);
    if (color !== state.turn) continue;
    const upper = piece.toUpperCase();
    if (upper === 'P') addPawnMoves(moves, board, from, color, state.ep);
    else if (upper === 'N') addStepMoves(moves, board, from, color, KNIGHT_DELTAS, [piece]);
    else if (upper === 'B') addSlidingMoves(moves, board, from, color, BISHOP_DIRS, [piece]);
    else if (upper === 'R') addSlidingMoves(moves, board, from, color, ROOK_DIRS, [piece]);
    else if (upper === 'Q') addSlidingMoves(moves, board, from, color, [...BISHOP_DIRS, ...ROOK_DIRS], [piece]);
    else if (upper === 'K') {
      addStepMoves(moves, board, from, color, KING_DELTAS, [piece]);
      addCastling(state, moves, color);
    }
  }
  return moves;
}

/** Copie profonde minimale d'un état. */
export const cloneState = (state) => ({
  board: state.board.slice(),
  turn: state.turn,
  castling: state.castling,
  ep: state.ep,
  half: state.half,
  full: state.full,
});

export function applyMove(state, move) {
  const next = cloneState(state);
  const board = next.board;
  const piece = board[move.from];
  const color = colorOf(piece);
  board[move.from] = null;

  // Prise en passant : le pion capturé n'est pas sur la case d'arrivée.
  if (move.ep) {
    const capturedSq = move.to + (color === 'w' ? 8 : -8);
    board[capturedSq] = null;
  }
  board[move.to] = move.promo ?? piece;

  // Roque : déplacer la tour.
  if (move.castle) {
    const homeRank = color === 'w' ? 7 : 0;
    if (move.castle === 'K') {
      board[at(5, homeRank)] = board[at(7, homeRank)];
      board[at(7, homeRank)] = null;
    } else {
      board[at(3, homeRank)] = board[at(0, homeRank)];
      board[at(0, homeRank)] = null;
    }
  }

  // Droits de roque : une pièce qui bouge ou une tour capturée les retire.
  let castling = next.castling;
  const remove = (letters) => {
    for (const letter of letters) castling = castling.replace(letter, '');
  };
  if (piece === 'K') remove('KQ');
  if (piece === 'k') remove('kq');
  if (piece === 'R' && move.from === 63) remove('K');
  if (piece === 'R' && move.from === 56) remove('Q');
  if (piece === 'r' && move.from === 7) remove('k');
  if (piece === 'r' && move.from === 0) remove('q');
  if (move.to === 63) remove('K');
  if (move.to === 56) remove('Q');
  if (move.to === 7) remove('k');
  if (move.to === 0) remove('q');
  next.castling = castling;

  // Case de prise en passant disponible au coup suivant.
  next.ep = move.double ? (move.from + move.to) / 2 : null;
  next.half = move.captured || piece.toUpperCase() === 'P' ? 0 : next.half + 1;
  if (color === 'b') next.full += 1;
  next.turn = color === 'w' ? 'b' : 'w';
  return next;
}

/** Coups strictement légaux : on vérifie que son propre roi n'est pas en échec après le coup. */
export function legalMoves(state) {
  const moves = pseudoMoves(state);
  const color = state.turn;
  return moves.filter((move) => !isInCheck(applyMove(state, move), color));
}

export function findMove(state, from, to, promo = null) {
  const moves = legalMoves(state).filter((m) => m.from === from && m.to === to);
  if (!moves.length) return null;
  if (promo) return moves.find((m) => m.promo && m.promo.toLowerCase() === promo.toLowerCase()) ?? moves[0];
  return moves.find((m) => m.promo === 'Q' || m.promo === 'q') ?? moves[0];
}

const PIECE_LETTER = { N: 'N', B: 'B', R: 'R', Q: 'Q', K: 'K' };

/** Notation algébrique, désambiguïsée, avec + et #. */
export function toSan(state, move) {
  const board = state.board;
  const piece = board[move.from];
  const upper = piece.toUpperCase();
  const capture = Boolean(move.captured) || Boolean(move.ep);
  let san;

  if (move.castle === 'K') san = 'O-O';
  else if (move.castle === 'Q') san = 'O-O-O';
  else if (upper === 'P') {
    san = capture ? `${FILES[fileOf(move.from)]}x${squareName(move.to)}` : squareName(move.to);
    if (move.promo) san += `=${move.promo.toUpperCase()}`;
  } else {
    const others = legalMoves(state).filter(
      (m) => m.to === move.to && m.from !== move.from && board[m.from]?.toUpperCase() === upper,
    );
    let disambiguation = '';
    if (others.length) {
      const sameFile = others.some((m) => fileOf(m.from) === fileOf(move.from));
      const sameRank = others.some((m) => rankOf(m.from) === rankOf(move.from));
      if (!sameFile) disambiguation = FILES[fileOf(move.from)];
      else if (!sameRank) disambiguation = String(8 - rankOf(move.from));
      else disambiguation = squareName(move.from);
    }
    san = `${PIECE_LETTER[upper]}${disambiguation}${capture ? 'x' : ''}${squareName(move.to)}`;
  }

  const after = applyMove(state, move);
  if (isInCheck(after)) san += legalMoves(after).length ? '+' : '#';
  return san;
}

/** Matériel insuffisant : aucune séquence ne peut mater. */
export function insufficientMaterial(board) {
  const pieces = board.filter(Boolean);
  if (pieces.length > 4) return false;
  const queensRooks = pieces.filter((p) => 'RQrq'.includes(p));
  if (queensRooks.length) return false;
  const knights = pieces.filter((p) => 'Nn'.includes(p));
  const bishops = pieces.filter((p) => 'Bb'.includes(p));
  const pawns = pieces.filter((p) => 'Pp'.includes(p));
  if (pawns.length) return false;
  if (knights.length + bishops.length <= 1) return true;
  if (knights.length === 2 || bishops.length === 2) {
    // Deux cavaliers ne suffisent pas seuls ; deux fous de même couleur non plus.
    if (knights.length === 2) return true;
    const squares = [];
    board.forEach((p, i) => { if ('Bb'.includes(p)) squares.push(i); });
    if (squares.length === 2) {
      const sameColor = (fileOf(squares[0]) + rankOf(squares[0])) % 2 === (fileOf(squares[1]) + rankOf(squares[1])) % 2;
      return sameColor;
    }
  }
  return false;
}

/**
 * État de la partie : `playing`, `check`, `checkmate`, `stalemate`, `draw-material`,
 * `draw-50`, `draw-repetition`.
 */
export function gameStatus(state, history = []) {
  const moves = legalMoves(state);
  const check = isInCheck(state);
  if (!moves.length) return check ? 'checkmate' : 'stalemate';
  if (insufficientMaterial(state.board)) return 'draw-material';
  if (state.half >= 100) return 'draw-50';
  const key = positionKey(state);
  const seen = history.filter((k) => k === key).length;
  if (seen >= 3) return 'draw-repetition';
  return check ? 'check' : 'playing';
}

/** Perft : nombre de coups légaux à une profondeur donnée (validation du générateur). */
export function perft(state, depth) {
  if (depth === 0) return 1;
  const moves = legalMoves(state);
  if (depth === 1) return moves.length;
  let total = 0;
  for (const move of moves) total += perft(applyMove(state, move), depth - 1);
  return total;
}

// ------------------------------------------------------------------ intelligence

const VALUES = { P: 100, N: 320, B: 330, R: 500, Q: 900, K: 20000 };
const CENTER_BONUS = [
  0, 0, 1, 2, 2, 1, 0, 0,
  0, 2, 3, 4, 4, 3, 2, 0,
  1, 3, 5, 6, 6, 5, 3, 1,
  2, 4, 6, 8, 8, 6, 4, 2,
  2, 4, 6, 8, 8, 6, 4, 2,
  1, 3, 5, 6, 6, 5, 3, 1,
  0, 2, 3, 4, 4, 3, 2, 0,
  0, 0, 1, 2, 2, 1, 0, 0,
];

function evaluate(state) {
  let score = 0;
  for (let sq = 0; sq < 64; sq += 1) {
    const piece = state.board[sq];
    if (!piece) continue;
    const value = VALUES[piece.toUpperCase()] + CENTER_BONUS[sq];
    score += isWhite(piece) ? value : -value;
  }
  return state.turn === 'w' ? score : -score;
}

function orderMoves(moves) {
  return moves.slice().sort((a, b) => (b.captured ? 1 : 0) - (a.captured ? 1 : 0));
}

function search(state, depth, alpha, beta) {
  if (depth === 0) return evaluate(state);
  const moves = orderMoves(legalMoves(state));
  if (!moves.length) return isInCheck(state) ? -99999 + depth : 0;
  let best = -Infinity;
  for (const move of moves) {
    const score = -search(applyMove(state, move), depth - 1, -beta, -alpha);
    if (score > best) best = score;
    if (best > alpha) alpha = best;
    if (alpha >= beta) break;
  }
  return best;
}

/** Meilleur coup à profondeur fixe (alpha-bêta). Retourne null si aucun coup. */
export function bestMove(state, depth = 3) {
  const moves = orderMoves(legalMoves(state));
  if (!moves.length) return null;
  let bestScore = -Infinity;
  let chosen = moves[0];
  for (const move of moves) {
    const score = -search(applyMove(state, move), depth - 1, -Infinity, Infinity);
    if (score > bestScore) {
      bestScore = score;
      chosen = move;
    }
  }
  return chosen;
}
