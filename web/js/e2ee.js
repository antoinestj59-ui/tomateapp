// TOMATE — chiffrement de bout en bout 1-1, côté navigateur.
//
// Mêmes algorithmes, mêmes encodages, même format que `core/.../crypto/E2ee.kt` :
//   P-256 + HKDF-SHA256 + AES-256-GCM, signature ECDSA P-256 (raw r||s).
// Ce module est le SEUL endroit où la PWA chiffre ou déchiffre ; il est vérifié contre
// l'implémentation Kotlin par `tooling/e2ee_interop.mjs` (exécuté par un test JVM).
//
// Limites assumées, affichées dans l'app : pas de Double Ratchet (donc pas de confidentialité
// persistante), une clé par appareil, pas de chiffrement de groupe.

export const E2EE_PREFIX = '[[tomate:e2ee:v1]]';
export const E2EE_INFO = 'tomate-e2ee-v1';
export const MAX_PLAINTEXT_BYTES = 2600;
const IV_BYTES = 12;
const TAG_BITS = 128;

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function isEncrypted(body) {
  return typeof body === 'string' && body.startsWith(E2EE_PREFIX);
}

export function b64uEncode(bytes) {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let binary = '';
  for (let i = 0; i < view.length; i += 1) binary += String.fromCharCode(view[i]);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function b64uDecode(text) {
  if (typeof text !== 'string' || !/^[A-Za-z0-9_-]+$/.test(text)) throw new Error('base64url invalide');
  const padded = text.length % 4 === 0 ? text : text + '='.repeat(4 - (text.length % 4));
  const binary = atob(padded.replace(/-/g, '+').replace(/_/g, '/'));
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i);
  return out;
}

const subtle = () => {
  const api = globalThis.crypto && globalThis.crypto.subtle;
  if (!api) throw new Error('WebCrypto indisponible : chiffrement impossible sur cette page');
  return api;
};

async function importAgreePublic(b64) {
  return subtle().importKey('raw', b64uDecode(b64), { name: 'ECDH', namedCurve: 'P-256' }, false, []);
}

async function importAgreePrivate(b64) {
  return subtle().importKey('pkcs8', b64uDecode(b64), { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
}

async function importSignPublic(b64) {
  return subtle().importKey('raw', b64uDecode(b64), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
}

async function importSignPrivate(b64) {
  return subtle().importKey('pkcs8', b64uDecode(b64), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
}

/** Dérive la clé AES du message : ECDH + HKDF(sel = SHA-256(clé éphémère)). */
async function deriveMessageKey(privateKey, peerPublicKey, ephemeralRaw) {
  const shared = await subtle().deriveBits({ name: 'ECDH', public: peerPublicKey }, privateKey, 256);
  const salt = await subtle().digest('SHA-256', ephemeralRaw);
  const base = await subtle().importKey('raw', shared, 'HKDF', false, ['deriveBits']);
  const bits = await subtle().deriveBits(
    { name: 'HKDF', hash: 'SHA-256', salt, info: encoder.encode(E2EE_INFO) },
    base,
    256,
  );
  return subtle().importKey('raw', bits, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
}

function associatedData(conversationId, senderId, epk) {
  return `${E2EE_INFO}|${conversationId}|${senderId}|${epk}`;
}

/** Génère l'identité d'appareil (deux paires : accord ECDH + signature ECDSA). */
export async function generateIdentity() {
  const agree = await subtle().generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const sign = await subtle().generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const agreePublic = b64uEncode(await subtle().exportKey('raw', agree.publicKey));
  const agreePrivate = b64uEncode(await subtle().exportKey('pkcs8', agree.privateKey));
  const signPublic = b64uEncode(await subtle().exportKey('raw', sign.publicKey));
  const signPrivate = b64uEncode(await subtle().exportKey('pkcs8', sign.privateKey));
  return {
    agreeKey: agreePublic,
    agreePrivate,
    signKey: signPublic,
    signPrivate,
    fingerprint: await fingerprint(signPublic),
  };
}

export async function encryptMessage({
  plaintext,
  conversationId,
  senderId,
  recipientAgreeKey,
  senderSignPrivate,
}) {
  if (!plaintext) throw new Error('message vide');
  const bytes = encoder.encode(plaintext);
  if (bytes.length > MAX_PLAINTEXT_BYTES) throw new Error('message trop long pour le chiffrement');
  if (!conversationId || !senderId) throw new Error('conversation ou expéditeur manquant');

  const ephemeral = await subtle().generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const ephemeralRaw = new Uint8Array(await subtle().exportKey('raw', ephemeral.publicKey));
  const epk = b64uEncode(ephemeralRaw);
  const aad = associatedData(conversationId, senderId, epk);

  const key = await deriveMessageKey(ephemeral.privateKey, await importAgreePublic(recipientAgreeKey), ephemeralRaw);
  const iv = new Uint8Array(IV_BYTES);
  globalThis.crypto.getRandomValues(iv);
  const ciphertext = new Uint8Array(
    await subtle().encrypt(
      { name: 'AES-GCM', iv, additionalData: encoder.encode(aad), tagLength: TAG_BITS },
      key,
      bytes,
    ),
  );
  const ivText = b64uEncode(iv);
  const cipherText = b64uEncode(ciphertext);
  const signature = new Uint8Array(
    await subtle().sign(
      { name: 'ECDSA', hash: 'SHA-256' },
      await importSignPrivate(senderSignPrivate),
      encoder.encode(`${aad}|${ivText}|${cipherText}`),
    ),
  );

  const body = `${E2EE_PREFIX}${senderId}|${epk}|${ivText}|${b64uEncode(signature)}|${cipherText}`;
  if (body.length > 4000) throw new Error('message chiffré trop long pour le serveur');
  return body;
}

export function parseBody(body, conversationId) {
  if (!isEncrypted(body)) return null;
  const fields = body.slice(E2EE_PREFIX.length).split('|');
  if (fields.length !== 5 || fields.some((field) => !field)) return null;
  return {
    fromId: fields[0],
    ephemeralKey: fields[1],
    iv: fields[2],
    signature: fields[3],
    ciphertext: fields[4],
    conversationId,
  };
}

export async function decryptMessage({
  body,
  conversationId,
  expectedSenderId,
  recipientAgreePrivate,
  senderSignPublic,
}) {
  const parsed = parseBody(body, conversationId);
  if (!parsed) throw new Error('message chiffré illisible');
  if (expectedSenderId && parsed.fromId !== expectedSenderId) throw new Error("expéditeur inattendu");

  const ephemeralRaw = b64uDecode(parsed.ephemeralKey);
  const aad = associatedData(conversationId, parsed.fromId, parsed.ephemeralKey);
  const signatureOk = await subtle().verify(
    { name: 'ECDSA', hash: 'SHA-256' },
    await importSignPublic(senderSignPublic),
    b64uDecode(parsed.signature),
    encoder.encode(`${aad}|${parsed.iv}|${parsed.ciphertext}`),
  );
  if (!signatureOk) throw new Error('signature invalide');

  const key = await deriveMessageKey(
    await importAgreePrivate(recipientAgreePrivate),
    await importAgreePublic(parsed.ephemeralKey),
    ephemeralRaw,
  );
  const plaintext = await subtle().decrypt(
    {
      name: 'AES-GCM',
      iv: b64uDecode(parsed.iv),
      additionalData: encoder.encode(aad),
      tagLength: TAG_BITS,
    },
    key,
    b64uDecode(parsed.ciphertext),
  );
  return decoder.decode(plaintext);
}

export async function fingerprint(publicKeyB64) {
  const digest = new Uint8Array(await subtle().digest('SHA-256', b64uDecode(publicKeyB64)));
  return b64uEncode(digest.slice(0, 8));
}

// ---------------------------------------------------------------------------------------------
// Identité locale et annuaire de contacts
//
// Les clés privées vivent dans le `localStorage` de l'appareil : elles ne partent jamais au
// serveur, mais un script injecté dans la page pourrait les lire. C'est une limite connue de la
// version web (l'app Android les garde dans le Keystore). Elle est écrite dans docs/04_SECURITE.md.
// ---------------------------------------------------------------------------------------------

const IDENTITY_STORAGE = 'tomate.e2ee.identity.v1';
const PEER_STORAGE = 'tomate.e2ee.peers.v1';

function readStore(key) {
  try {
    const raw = globalThis.localStorage?.getItem(key);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function writeStore(key, value) {
  try {
    globalThis.localStorage?.setItem(key, JSON.stringify(value));
  } catch {
    // Mode privé ou quota plein : l'identité ne survivra pas au rechargement, on ne bloque pas
    // l'utilisateur pour autant.
  }
}

/** Charge (ou crée) l'identité de l'appareil pour ce compte. */
export async function ensureIdentity(userId = null) {
  const existing = readStore(IDENTITY_STORAGE);
  if (existing && existing.agreePrivate && existing.signPrivate) {
    if (!userId || !existing.userId || existing.userId === userId) return existing;
    // Un autre compte se connecte : ses clés ne doivent pas servir à l'ancien.
  }
  const generated = await generateIdentity();
  const stored = { ...generated, userId, createdAt: Date.now() };
  writeStore(IDENTITY_STORAGE, stored);
  return stored;
}

export function loadPeerKeys(userId) {
  const peers = readStore(PEER_STORAGE) || {};
  return peers[userId] || null;
}

export function savePeerKeys(userId, handle, agreeKey, signKey, source = 'pairing') {
  if (!userId || !agreeKey || !signKey) return false;
  const peers = readStore(PEER_STORAGE) || {};
  peers[userId] = { userId, handle: handle || '', agreeKey, signKey, source, at: Date.now() };
  writeStore(PEER_STORAGE, peers);
  return true;
}

/** Lien d'appairage : identité + clés publiques, à mettre en QR, en NFC ou à envoyer. */
export async function pairingLink(userId, handle) {
  const identity = await ensureIdentity(userId);
  const params = new URLSearchParams({
    v: '1',
    u: userId,
    n: handle || '',
    a: identity.agreeKey,
    s: identity.signKey,
  });
  return `tomate://pair?${params.toString()}`;
}

/** Numéro de sécurité entre l'appareil et un contact connu. */
export async function safetyNumberFor(userId, peer) {
  const me = await ensureIdentity(userId);
  return safetyNumber(
    { id: userId, agreeKey: me.agreeKey, signKey: me.signKey },
    { id: peer.userId, agreeKey: peer.agreeKey, signKey: peer.signKey },
  );
}

/** Numéro de sécurité : identique des deux côtés, à comparer hors bande. */
export async function safetyNumber(first, second) {
  const entries = [
    `${first.id}:${first.agreeKey}:${first.signKey}`,
    `${second.id}:${second.agreeKey}:${second.signKey}`,
  ].sort();
  const digest = new Uint8Array(await subtle().digest('SHA-256', encoder.encode(entries.join('|'))));
  const groups = [];
  for (let i = 0; i < 8; i += 1) {
    const value = (digest[i * 2] << 8) | digest[i * 2 + 1];
    groups.push(String(value).padStart(5, '0'));
  }
  return groups.join(' ');
}
