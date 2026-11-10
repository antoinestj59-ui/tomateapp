// Vérification d'interopérabilité du chiffrement de bout en bout TOMATE.
//
// Ce script charge le VRAI module de la PWA (`web/js/e2ee.js`), déchiffre un message produit par
// le code Kotlin, vérifie sa signature, puis chiffre une réponse qui sera déchiffrée par Kotlin.
// Il est lancé par `E2eeInteropTest` : si les deux implémentations divergent d'un seul octet,
// le test JVM échoue.
//
// Usage : node tooling/e2ee_interop.mjs <chemin e2ee.js> <fixture.json> <sortie.json>

import { webcrypto } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

if (!globalThis.crypto) globalThis.crypto = webcrypto;
if (!globalThis.btoa) {
  globalThis.btoa = (binary) => Buffer.from(binary, 'binary').toString('base64');
  globalThis.atob = (text) => Buffer.from(text, 'base64').toString('binary');
}

const [, , modulePath, fixturePath, outputPath] = process.argv;
if (!modulePath || !fixturePath || !outputPath) {
  console.error('usage: node e2ee_interop.mjs <e2ee.js> <fixture.json> <out.json>');
  process.exit(2);
}

const source = readFileSync(modulePath, 'utf8');
const e2ee = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));

const plaintextFromKotlin = await e2ee.decryptMessage({
  body: fixture.body,
  conversationId: fixture.conversationId,
  expectedSenderId: fixture.aliceId,
  recipientAgreePrivate: fixture.bobAgreePrivate,
  senderSignPublic: fixture.aliceSignKey,
});

const replyText = 'Réponse de Bob, chiffrée par WebCrypto.';
const replyBody = await e2ee.encryptMessage({
  plaintext: replyText,
  conversationId: fixture.conversationId,
  senderId: fixture.bobId,
  recipientAgreeKey: fixture.aliceAgreeKey,
  senderSignPrivate: fixture.bobSignPrivate,
});

const safetyFromWeb = await e2ee.safetyNumber(
  { id: fixture.aliceId, agreeKey: fixture.aliceAgreeKey, signKey: fixture.aliceSignKey },
  { id: fixture.bobId, agreeKey: fixture.bobAgreeKey, signKey: fixture.bobSignKey },
);

// --- Couche identité/appairage de la PWA (localStorage simulé) ---------------------------------
const store = new Map();
globalThis.localStorage = {
  getItem: (key) => (store.has(key) ? store.get(key) : null),
  setItem: (key, value) => store.set(key, String(value)),
  removeItem: (key) => store.delete(key),
};

const identityA = await e2ee.ensureIdentity('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
const identityB = await e2ee.ensureIdentity('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
if (identityA.agreeKey === identityB.agreeKey) throw new Error('deux comptes ne doivent pas partager une clé');

const link = await e2ee.pairingLink('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'alice');
if (!link.startsWith('tomate://pair?v=1&')) throw new Error(`lien d'appairage inattendu : ${link}`);
e2ee.savePeerKeys(
  'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  'bob',
  identityB.agreeKey,
  identityB.signKey,
  'pairing',
);
const peer = e2ee.loadPeerKeys('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
if (!peer || peer.agreeKey !== identityB.agreeKey) throw new Error('les clés appairées ne sont pas relues');

const webSafety = await e2ee.safetyNumberFor('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', {
  userId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  agreeKey: identityB.agreeKey,
  signKey: identityB.signKey,
});
if (!/^\d{5}( \d{5}){7}$/.test(webSafety)) throw new Error(`numéro de sécurité mal formé : ${webSafety}`);

let tamperRejected = false;
try {
  const tampered = replyBody.slice(0, -6) + 'AAAAAA';
  await e2ee.decryptMessage({
    body: tampered,
    conversationId: fixture.conversationId,
    expectedSenderId: fixture.bobId,
    recipientAgreePrivate: fixture.aliceAgreePrivate,
    senderSignPublic: fixture.bobSignKey,
  });
} catch (error) {
  tamperRejected = true;
}

writeFileSync(
  outputPath,
  JSON.stringify(
    {
      plaintextFromKotlin,
      replyBody,
      replyText,
      safetyFromWeb,
      webSafety,
      pairingLink: link,
      identityPersisted: store.size > 0,
      tamperRejected,
      moduleBytes: source.length,
    },
    null,
    2,
  ),
);

console.log(`OK déchiffré par la PWA : ${JSON.stringify(plaintextFromKotlin)}`);
