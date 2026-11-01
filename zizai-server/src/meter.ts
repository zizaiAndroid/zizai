import type { Decision, Kind, Plan, Usage } from './quota.ts';

/**
 * The atomic parts, as interfaces: per-account counting, and the global spend ceiling. In the Worker
 * both are Durable Objects (`objects.ts`), which Cloudflare runs one call at a time per object, so
 * "is there room, then count it" cannot race. Tests use in-memory versions.
 */
export interface Meter {
  take(accountId: string, kind: Kind, plan: Plan): Promise<Decision>;
  refundAction(accountId: string): Promise<void>;
  recordAudio(accountId: string, seconds: number, plan: Plan): Promise<Usage>;
  usage(accountId: string, plan: Plan): Promise<Usage>;
  erase(accountId: string): Promise<void>;
}

export interface Guard {
  /** Reserves estimated spend for today; false when the day's budget is used up or the switch is off. */
  reserve(estimateMicros: number): Promise<boolean>;
  /** Corrects a reservation once the real cost is known (negative gives back). */
  settle(deltaMicros: number): Promise<void>;
}
