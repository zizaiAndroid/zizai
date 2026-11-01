/** Hashes, tokens and the signed install nonce. WebCrypto only, so it runs in Workers and in Node. */

const encoder = new TextEncoder();

export async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(input));
  return hex(new Uint8Array(digest));
}

export async function hmacHex(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const signature = await crypto.subtle.sign('HMAC', key, encoder.encode(message));
  return hex(new Uint8Array(signature));
}

/** A bearer token: 32 random bytes. Only its SHA-256 is ever stored. */
export function newToken(): string {
  return `zz_${base64url(crypto.getRandomValues(new Uint8Array(32)))}`;
}

/**
 * A nonce for Play Integrity: `<issuedAt>.<random>.<hmac>`, base64url throughout, which is the
 * alphabet Play Integrity requires (URL-safe, no wrap, no padding).
 *
 * Stateless: the signature proves the server issued it, the timestamp bounds its life. It can be
 * replayed within that window, but only together with an integrity token Google issued for it, on
 * the same device, which gains nothing: the install it buys is the one already there.
 */
export async function issueNonce(secret: string, now: number): Promise<string> {
  const body = `${now.toString(36)}.${base64url(crypto.getRandomValues(new Uint8Array(16)))}`;
  const signature = (await hmacHex(secret, `nonce:${body}`)).slice(0, 32);
  return `${body}.${signature}`;
}

export type NonceCheck = { ok: true } | { ok: false; reason: 'malformed' | 'signature' | 'expired' };

export async function verifyNonce(secret: string, nonce: string, now: number, ttlMs: number): Promise<NonceCheck> {
  const parts = nonce.split('.');
  if (parts.length !== 3) return { ok: false, reason: 'malformed' };
  const [issued, random, signature] = parts as [string, string, string];
  const expected = (await hmacHex(secret, `nonce:${issued}.${random}`)).slice(0, 32);
  if (!timingSafeEqual(expected, signature)) return { ok: false, reason: 'signature' };
  const issuedAt = parseInt(issued, 36);
  if (!Number.isFinite(issuedAt) || issuedAt > now + 60_000 || now - issuedAt > ttlMs) {
    return { ok: false, reason: 'expired' };
  }
  return { ok: true };
}

export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export function base64url(bytes: Uint8Array): string {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function hex(bytes: Uint8Array): string {
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
}
