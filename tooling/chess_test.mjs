// Validation du moteur d'échecs par des valeurs de référence (perft) et des règles précises.
// Exécution : node tooling/chess_test.mjs
import {
  START_FEN, parseFen, legalMoves, applyMove, toSan, gameStatus,
  perft, isInCheck, findMove, positionKey, bestMove, squareIndex, toFen,
} from '../web/js/chess.js';

let pass = 0;
let fail = 0;

function check(label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (ok) {
    pass += 1;
    console.log(`PASS  ${label}`);
  } else {
    fail += 1;
    console.log(`FAIL  ${label}\n      attendu ${JSON.stringify(expected)}\n      obtenu  ${JSON.stringify(actual)}`);
  }
}

// ------------------------------------------------------------------ perft
const start = parseFen(START_FEN);
check('perft départ profondeur 1', perft(start, 1), 20);
check('perft départ profondeur 2', perft(start, 2), 400);
check('perft départ profondeur 3', perft(start, 3), 8902);
check('perft départ profondeur 4', perft(start, 4), 197281);

// Kiwipete : roque, en passant, promotions, clouages.
const kiwipete = parseFen('r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1');
check('perft Kiwipete profondeur 1', perft(kiwipete, 1), 48);
check('perft Kiwipete profondeur 2', perft(kiwipete, 2), 2039);
check('perft Kiwipete profondeur 3', perft(kiwipete, 3), 97862);

// ------------------------------------------------------------------ règles
// Prise en passant disponible et légale.
const ep = parseFen('rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3');
const epMove = findMove(ep, squareIndex('e5'), squareIndex('f6'));
check('prise en passant existe', Boolean(epMove), true);
check('prise en passant est marquée', Boolean(epMove?.ep), true);
check('prise en passant en SAN', toSan(ep, epMove), 'exf6');

// Promotion : quatre choix, SAN avec =Q.
const promo = parseFen('2k5/4P3/8/8/8/8/8/4K3 w - - 0 1');
const promoMoves = legalMoves(promo).filter((m) => m.to === squareIndex('e8'));
check('quatre promotions', promoMoves.length, 4);
check('promotion en dame notée', toSan(promo, promoMoves.find((m) => m.promo === 'Q')), 'e8=Q+');

// Roque : les deux côtés, et interdit à travers un échec.
const castle = parseFen('r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1');
check('petit roque blanc disponible', Boolean(findMove(castle, squareIndex('e1'), squareIndex('g1'))), true);
check('grand roque blanc disponible', Boolean(findMove(castle, squareIndex('e1'), squareIndex('c1'))), true);
const castleBlocked = parseFen('r3k2r/8/8/8/8/8/5r2/R3K2R w KQkq - 0 1');
check('roque interdit à travers une case attaquée', findMove(castleBlocked, squareIndex('e1'), squareIndex('g1')), null);

// Mat du berger, mat du imbécile, pat.
const fool = parseFen('rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3');
check('échec et mat détecté', gameStatus(fool), 'checkmate');
check('aucun coup en position de mat', legalMoves(fool).length, 0);

const stalemate = parseFen('7k/5Q2/6K1/8/8/8/8/8 b - - 0 1');
check('pat détecté', gameStatus(stalemate), 'stalemate');

const bare = parseFen('8/8/4k3/8/8/4K3/8/8 w - - 0 1');
check('matériel insuffisant (roi contre roi)', gameStatus(bare), 'draw-material');

// SAN : ouverture, désambiguïsation, capture.
const opening = parseFen(START_FEN);
const e4 = findMove(opening, squareIndex('e2'), squareIndex('e4'));
check('e4 en SAN', toSan(opening, e4), 'e4');
const afterE4 = applyMove(opening, e4);
const e5 = findMove(afterE4, squareIndex('e7'), squareIndex('e5'));
check('e5 noir existe', Boolean(e5), true);
const afterE5 = applyMove(afterE4, e5);
const nf3 = findMove(afterE5, squareIndex('g1'), squareIndex('f3'));
check('Cf3 existe', Boolean(nf3), true);
check('Cf3 en SAN', toSan(afterE5, nf3), 'Nf3');

// Deux cavaliers pouvant aller sur la même case : désambiguïsation par colonne.
const ambiguous = parseFen('8/8/8/8/8/2N1N3/8/K6k w - - 0 1');
const toD5 = legalMoves(ambiguous).find((m) => m.to === squareIndex('d5') && m.from === squareIndex('c3'));
check('désambiguïsation Cd5', toSan(ambiguous, toD5), 'Ncd5');

// Échec simple.
const checkState = parseFen('4k3/8/8/8/8/8/4R3/4K3 b - - 0 1');
check('échec détecté', isInCheck(checkState), true);
check('statut échec', gameStatus(checkState), 'check');

// Le roi ne peut pas rester en échec, et une pièce clouée ne peut pas bouger.
const pinned = parseFen('4k3/8/8/8/8/8/4R3/4K3 b - - 0 1');
check('le roi fuit l’échec', legalMoves(pinned).every((m) => m.to !== squareIndex('e8')), true);

// Répétition triple : clé de position stable pour la même position.
const repeat = parseFen(START_FEN);
check('clé de position stable', positionKey(repeat), positionKey(parseFen(START_FEN)));

// L’IA doit produire un coup légal et rapide.
const aiState = parseFen('r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4');
const aiMove = bestMove(aiState, 2);
check('l’IA propose un coup légal', Boolean(legalMoves(aiState).find((m) => m.from === aiMove.from && m.to === aiMove.to)), true);

// FEN aller-retour (sauvegarde de partie).
check('FEN aller-retour', toFen(parseFen(START_FEN)), START_FEN);

console.log(`\n${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
