import type { GoogleApi } from './google.ts';
import type { Guard, Meter } from './meter.ts';
import type { Settings } from './settings.ts';
import type { Store } from './store.ts';
import type { Upstream } from './upstream.ts';

/** Everything a route needs, assembled once per request in `index.ts` and from fakes in the tests. */
export interface Services {
  settings: Settings;
  store: Store;
  meter: Meter;
  guard: Guard;
  /** Null when the Google service account is not configured (local development). */
  google: GoogleApi | null;
  upstream: Upstream;
  /** Signs nonces and keys device accounts. A Worker secret. */
  secret: string;
  rtdnSecret: string | null;
  now(): number;
  /** Work that may finish after the response (acknowledging a purchase, touching last-seen). */
  later(work: Promise<unknown>): void;
}
