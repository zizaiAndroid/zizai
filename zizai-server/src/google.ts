import { base64url } from './crypto.ts';
import type { IntegrityPayload } from './integrity.ts';
import type { GoogleSubscription } from './subscription.ts';

/**
 * The three Google calls the server makes: decode a Play Integrity token, read a subscription, and
 * acknowledge one. All of them authenticate as the Play-linked service account with a self-signed JWT,
 * because a Worker has no Google client library.
 *
 * Behind an interface so routes can be tested with recorded answers.
 */
export interface GoogleApi {
  decodeIntegrity(token: string): Promise<IntegrityPayload>;
  /** Null when Google does not know the token (an invented one). Throws when Google is unreachable. */
  subscription(purchaseToken: string): Promise<GoogleSubscription | null>;
  acknowledge(productId: string, purchaseToken: string): Promise<void>;
}

interface ServiceAccount {
  client_email: string;
  private_key: string;
}

const SCOPES = [
  'https://www.googleapis.com/auth/androidpublisher',
  'https://www.googleapis.com/auth/playintegrity',
].join(' ');

export class HttpGoogleApi implements GoogleApi {
  private cached: { value: string; expiresAt: number } | null = null;
  private readonly serviceAccountJson: string;
  private readonly packageName: string;
  private readonly fetcher: typeof fetch;

  constructor(serviceAccountJson: string, packageName: string, fetcher: typeof fetch = (input, init) => fetch(input, init)) {
    this.serviceAccountJson = serviceAccountJson;
    this.packageName = packageName;
    // Wrapped, not stored bare: Workers throw "Illegal invocation" when fetch runs with another `this`.
    this.fetcher = fetcher;
  }

  async decodeIntegrity(token: string): Promise<IntegrityPayload> {
    const response = await this.fetcher(
      `https://playintegrity.googleapis.com/v1/${encodeURIComponent(this.packageName)}:decodeIntegrityToken`,
      {
        method: 'POST',
        headers: { authorization: `Bearer ${await this.accessToken()}`, 'content-type': 'application/json' },
        body: JSON.stringify({ integrity_token: token }),
      },
    );
    if (!response.ok) throw new GoogleError(`decodeIntegrityToken answered ${response.status}`, response.status);
    const body = (await response.json()) as { tokenPayloadExternal?: IntegrityPayload };
    return body.tokenPayloadExternal ?? {};
  }

  async subscription(purchaseToken: string): Promise<GoogleSubscription | null> {
    const response = await this.fetcher(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(this.packageName)}` +
        `/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`,
      { headers: { authorization: `Bearer ${await this.accessToken()}` } },
    );
    if (response.status === 404 || response.status === 400 || response.status === 410) return null;
    if (!response.ok) throw new GoogleError(`subscriptionsv2 answered ${response.status}`, response.status);
    return (await response.json()) as GoogleSubscription;
  }

  async acknowledge(productId: string, purchaseToken: string): Promise<void> {
    const response = await this.fetcher(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(this.packageName)}` +
        `/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`,
      {
        method: 'POST',
        headers: { authorization: `Bearer ${await this.accessToken()}`, 'content-type': 'application/json' },
        body: '{}',
      },
    );
    if (!response.ok) throw new GoogleError(`acknowledge answered ${response.status}`, response.status);
  }

  private async accessToken(): Promise<string> {
    if (this.cached && this.cached.expiresAt > Date.now() + 60_000) return this.cached.value;
    const account = JSON.parse(this.serviceAccountJson) as ServiceAccount;
    const now = Math.floor(Date.now() / 1000);
    const encode = (value: object) => base64url(new TextEncoder().encode(JSON.stringify(value)));
    const unsigned = `${encode({ alg: 'RS256', typ: 'JWT' })}.${encode({
      iss: account.client_email,
      scope: SCOPES,
      aud: 'https://oauth2.googleapis.com/token',
      iat: now,
      exp: now + 3600,
    })}`;
    const key = await importPrivateKey(account.private_key);
    const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned));
    const response = await this.fetcher('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion: `${unsigned}.${base64url(new Uint8Array(signature))}`,
      }),
    });
    if (!response.ok) throw new GoogleError(`Google rejected the service account (${response.status})`, response.status);
    const token = (await response.json()) as { access_token: string; expires_in: number };
    this.cached = { value: token.access_token, expiresAt: Date.now() + token.expires_in * 1000 };
    return token.access_token;
  }
}

export class GoogleError extends Error {
  readonly status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const body = pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, '').replace(/\s+/g, '');
  const raw = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey('pkcs8', raw, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
}
