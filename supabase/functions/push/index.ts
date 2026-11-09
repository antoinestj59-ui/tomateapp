// TOMATE — envoi des notifications vers les appareils Android via Firebase Cloud Messaging.
//
// Déclenché par un webhook Supabase sur `notifications` (INSERT). Le corps attendu est celui
// d'un Database Webhook : { type, table, record }.
//
// Secrets nécessaires (Supabase → Edge Functions → Secrets) :
//   FCM_SERVICE_ACCOUNT : JSON complet du compte de service Firebase (une ligne)
//   PUSH_WEBHOOK_SECRET : chaîne aléatoire, identique à l'en-tête x-webhook-secret du webhook
// SUPABASE_URL et SUPABASE_SERVICE_ROLE_KEY sont fournis automatiquement.
//
// Déploiement : voir docs/10_PUSH_FIREBASE.md.

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

const enc = new TextEncoder();

function base64url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const body = pem.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  const der = Uint8Array.from(atob(body), (char) => char.charCodeAt(0));
  return crypto.subtle.importKey(
    'pkcs8',
    der,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
}

async function googleAccessToken(serviceAccount: {
  client_email: string;
  private_key: string;
}): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = base64url(enc.encode(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const claims = base64url(
    enc.encode(
      JSON.stringify({
        iss: serviceAccount.client_email,
        scope: 'https://www.googleapis.com/auth/firebase.messaging',
        aud: 'https://oauth2.googleapis.com/token',
        iat: now,
        exp: now + 3600,
      }),
    ),
  );
  const unsigned = `${header}.${claims}`;
  const key = await importPrivateKey(serviceAccount.private_key);
  const signature = new Uint8Array(
    await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, enc.encode(unsigned)),
  );
  const assertion = `${unsigned}.${base64url(signature)}`;

  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion,
    }),
  });
  if (!response.ok) {
    throw new Error(`OAuth Google refusé (${response.status}): ${await response.text()}`);
  }
  const json = await response.json();
  return json.access_token as string;
}

function notificationText(kind: string, body: string, actorName: string | null): string {
  const who = actorName ?? 'Quelqu’un';
  switch (kind) {
    case 'message':
      return `${who} : ${body}`.slice(0, 180);
    case 'reaction':
      return `${who} a réagi à votre publication`;
    case 'comment':
      return `${who} a commenté : ${body}`.slice(0, 180);
    case 'join':
      return body.startsWith('votre demande') ? body : `${who} ${body}`.slice(0, 180);
    default:
      return body.slice(0, 180) || 'Nouvelle activité sur TOMATE';
  }
}

Deno.serve(async (request) => {
  if (request.method !== 'POST') return new Response('method not allowed', { status: 405 });

  const secret = Deno.env.get('PUSH_WEBHOOK_SECRET');
  if (!secret || request.headers.get('x-webhook-secret') !== secret) {
    return new Response('unauthorized', { status: 401 });
  }

  let payload: { record?: Record<string, unknown> };
  try {
    payload = await request.json();
  } catch {
    return new Response('invalid json', { status: 400 });
  }
  const record = payload.record;
  if (!record?.user_id) return new Response('no record', { status: 400 });

  const serviceAccountRaw = Deno.env.get('FCM_SERVICE_ACCOUNT');
  if (!serviceAccountRaw) {
    return new Response(JSON.stringify({ sent: 0, reason: 'FCM_SERVICE_ACCOUNT absent' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }
  const serviceAccount = JSON.parse(serviceAccountRaw);

  const supabase = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
  );

  const userId = String(record.user_id);
  const kind = String(record.kind ?? 'system');

  const { data: tokens, error } = await supabase
    .from('device_tokens')
    .select('token')
    .eq('user_id', userId);
  if (error) return new Response(JSON.stringify({ error: error.message }), { status: 500 });
  if (!tokens?.length) return new Response(JSON.stringify({ sent: 0 }), { status: 200 });

  let actorName: string | null = null;
  if (record.actor_id) {
    const { data: actor } = await supabase
      .from('profiles')
      .select('display_name, handle')
      .eq('id', String(record.actor_id))
      .maybeSingle();
    actorName = actor?.display_name || actor?.handle || null;
  }

  const accessToken = await googleAccessToken(serviceAccount);
  const endpoint = `https://fcm.googleapis.com/v1/projects/${serviceAccount.project_id}/messages:send`;
  const text = notificationText(kind, String(record.body ?? ''), actorName);

  const stale: string[] = [];
  let sent = 0;
  for (const row of tokens) {
    const message = {
      message: {
        token: row.token,
        notification: { title: 'TOMATE', body: text },
        android: { priority: 'HIGH', notification: { channel_id: 'tomate_messages' } },
        data: {
          kind,
          conversation_id: String(record.conversation_id ?? ''),
          entity_id: String(record.entity_id ?? ''),
        },
      },
    };
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(message),
    });
    if (response.ok) {
      sent += 1;
      continue;
    }
    const detail = await response.text();
    // UNREGISTERED / INVALID_ARGUMENT: the token is dead, remove it so we stop paying for it.
    if (response.status === 404 || detail.includes('UNREGISTERED') || detail.includes('INVALID_ARGUMENT')) {
      stale.push(row.token);
    }
  }

  if (stale.length) {
    await supabase.from('device_tokens').delete().in('token', stale).eq('user_id', userId);
  }

  return new Response(JSON.stringify({ sent, removed: stale.length }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
});
