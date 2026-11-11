// Génère deux identités E2EE, chiffre un message 1-1 avec le VRAI module de la PWA, et imprime
// le JSON consommé par `tooling/verify_050_e2ee.sh`.
//
//   node tooling/e2ee_seal.mjs <aliceId> <bobId> <conversationId>
//
// Aucun secret réel : les clés sont jetables et ne servent qu'à la vérification.

import { webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';

if (!globalThis.crypto) globalThis.crypto = webcrypto;
if (!globalThis.btoa) {
  globalThis.btoa = (binary) => Buffer.from(binary, 'binary').toString('base64');
  globalThis.atob = (text) => Buffer.from(text, 'base64').toString('binary');
}

const [, , aliceId, bobId, conversationId] = process.argv;
if (!aliceId || !bobId || !conversationId) {
  console.error('usage: node e2ee_seal.mjs <aliceId> <bobId> <conversationId>');
  process.exit(2);
}

const source = readFileSync(new URL('../web/js/e2ee.js', import.meta.url), 'utf8');
const e2ee = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

const alice = await e2ee.generateIdentity();
const bob = await e2ee.generateIdentity();
const plaintext = 'Message chiffré envoyé par le script de vérification.';

const body = await e2ee.encryptMessage({
  plaintext,
  conversationId,
  senderId: aliceId,
  recipientAgreeKey: bob.agreeKey,
  senderSignPrivate: alice.signPrivate,
});

// Contrôle immédiat : Bob doit relire exactement ce texte.
const roundTrip = await e2ee.decryptMessage({
  body,
  conversationId,
  expectedSenderId: aliceId,
  recipientAgreePrivate: bob.agreePrivate,
  senderSignPublic: alice.signKey,
});

if (roundTrip !== plaintext) {
  console.error('aller-retour local cassé');
  process.exit(1);
}

console.log(JSON.stringify({
  plaintext,
  aliceAgree: alice.agreeKey,
  aliceSign: alice.signKey,
  bobAgree: bob.agreeKey,
  bobSign: bob.signKey,
  body,
}));
