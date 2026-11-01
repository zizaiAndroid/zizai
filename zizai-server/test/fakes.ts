import type { GoogleApi } from '../src/google.ts';
import type { IntegrityPayload } from '../src/integrity.ts';
import type { Guard, Meter } from '../src/meter.ts';
import {
  decide,
  type GuardState,
  type MeterState,
  recordAudio,
  refundAction,
  reserveSpend,
  rollOver,
  settleSpend,
  usageOf,
} from '../src/quota.ts';
import type { Services } from '../src/services.ts';
import { settingsFrom, type Settings } from '../src/settings.ts';
import type { Store, SubscriptionRow } from '../src/store.ts';
import type { GoogleSubscription } from '../src/subscription.ts';
import type { Upstream, UpstreamResult } from '../src/upstream.ts';

export class MemoryStore implements Store {
  accounts = new Map<string, { deviceKey: string; lastSeen: number }>();
  tokens = new Map<string, { accountId: string; createdAt: number }>();
  subs = new Map<string, SubscriptionRow>();
  links: Array<{ token: string; accountId: string; at: number }> = [];

  async accountByDevice(deviceKey: string) {
    for (const [id, a] of this.accounts) if (a.deviceKey === deviceKey) return id;
    return null;
  }
  async createAccount(id: string, deviceKey: string, now: number) {
    this.accounts.set(id, { deviceKey, lastSeen: now });
  }
  async addToken(hash: string, accountId: string, now: number, keep: number) {
    this.tokens.set(hash, { accountId, createdAt: now });
    const mine = [...this.tokens.entries()].filter(([, t]) => t.accountId === accountId).sort((a, b) => b[1].createdAt - a[1].createdAt);
    for (const [h] of mine.slice(keep)) this.tokens.delete(h);
  }
  async accountByToken(hash: string) {
    return this.tokens.get(hash)?.accountId ?? null;
  }
  async touch(accountId: string, _hash: string, now: number) {
    const a = this.accounts.get(accountId);
    if (a) a.lastSeen = now;
  }
  async accountExists(id: string) {
    return this.accounts.has(id);
  }
  async proUntil(accountId: string, now: number) {
    let until = 0;
    for (const l of this.links) {
      const s = this.subs.get(l.token);
      if (l.accountId === accountId && s && s.entitled && !s.replacedBy && s.expiresAt > now) until = Math.max(until, s.expiresAt);
    }
    return until;
  }
  async subscription(token: string) {
    return this.subs.get(token) ?? null;
  }
  async saveSubscription(row: SubscriptionRow) {
    const old = this.subs.get(row.purchaseToken);
    this.subs.set(row.purchaseToken, { ...row, replacedBy: old?.replacedBy ?? row.replacedBy });
  }
  async markReplaced(oldToken: string, newToken: string, now: number) {
    const s = this.subs.get(oldToken);
    if (s) this.subs.set(oldToken, { ...s, replacedBy: newToken, entitled: false, updatedAt: now });
  }
  async linkedAccounts(token: string) {
    return this.links.filter((l) => l.token === token).map((l) => l.accountId);
  }
  async link(token: string, accountId: string, now: number) {
    if (!this.links.some((l) => l.token === token && l.accountId === accountId)) this.links.push({ token, accountId, at: now });
  }
  async deleteAccount(id: string) {
    this.accounts.delete(id);
    for (const [h, t] of this.tokens) if (t.accountId === id) this.tokens.delete(h);
    this.links = this.links.filter((l) => l.accountId !== id);
  }
  async lapsedSubscriptions(now: number) {
    return [...this.subs.values()].filter((s) => s.entitled && s.expiresAt <= now).map((s) => s.purchaseToken);
  }
  async staleAccounts(cutoff: number, now: number) {
    const out: string[] = [];
    for (const [id, a] of this.accounts) if (a.lastSeen < cutoff && (await this.proUntil(id, now)) === 0) out.push(id);
    return out;
  }
}

export class MemoryMeter implements Meter {
  states = new Map<string, MeterState>();
  hits = new Map<string, number[]>();
  private readonly settings: Settings;
  private readonly clock: () => number;
  constructor(settings: Settings, clock: () => number) {
    this.settings = settings;
    this.clock = clock;
  }

  private state(id: string) {
    return rollOver(this.states.get(id), this.clock());
  }
  async take(id: string, kind: 'action' | 'audio', plan: 'free' | 'pro') {
    const r = decide(this.state(id), this.hits.get(id) ?? [], kind, plan, this.settings, this.clock());
    this.states.set(id, r.state);
    this.hits.set(id, r.hits);
    return r.decision;
  }
  async refundAction(id: string) {
    this.states.set(id, refundAction(this.state(id)));
  }
  async recordAudio(id: string, seconds: number, plan: 'free' | 'pro') {
    const next = recordAudio(this.state(id), seconds);
    this.states.set(id, next);
    return usageOf(next, plan, this.settings, this.clock());
  }
  async usage(id: string, plan: 'free' | 'pro') {
    return usageOf(this.state(id), plan, this.settings, this.clock());
  }
  async erase(id: string) {
    this.states.delete(id);
    this.hits.delete(id);
  }
}

export class MemoryGuard implements Guard {
  state: GuardState | undefined;
  private readonly budgetUsd: number;
  private readonly clock: () => number;
  constructor(budgetUsd: number, clock: () => number) {
    this.budgetUsd = budgetUsd;
    this.clock = clock;
  }
  async reserve(micros: number) {
    const r = reserveSpend(this.state, micros, this.budgetUsd, this.clock());
    this.state = r.state;
    return r.ok;
  }
  async settle(delta: number) {
    this.state = settleSpend(this.state, delta, this.clock());
  }
}

export class FakeGoogle implements GoogleApi {
  integrity: IntegrityPayload | ((token: string) => IntegrityPayload) = {};
  subscriptions = new Map<string, GoogleSubscription>();
  acknowledged: string[] = [];
  down = false;

  async decodeIntegrity(token: string) {
    if (this.down) throw new Error('down');
    return typeof this.integrity === 'function' ? this.integrity(token) : this.integrity;
  }
  async subscription(token: string) {
    if (this.down) throw new Error('down');
    return this.subscriptions.get(token) ?? null;
  }
  async acknowledge(_productId: string, token: string) {
    this.acknowledged.push(token);
  }
}

export class FakeUpstream implements Upstream {
  chats: Array<Record<string, unknown>> = [];
  transcriptions: FormData[] = [];
  chatAnswer: UpstreamResult = {
    ok: true,
    status: 200,
    via: 'groq',
    body: JSON.stringify({ choices: [{ message: { role: 'assistant', content: 'Fixed.' }, finish_reason: 'stop' }], usage: { prompt_tokens: 50, completion_tokens: 20 } }),
  };
  transcriptionAnswer: UpstreamResult = { ok: true, status: 200, via: 'groq', body: JSON.stringify({ text: '你好', duration: 12.4 }) };

  async chat(body: Record<string, unknown>) {
    this.chats.push(body);
    return this.chatAnswer;
  }
  async transcribe(form: FormData) {
    this.transcriptions.push(form);
    return this.transcriptionAnswer;
  }
}

export interface World {
  s: Services;
  store: MemoryStore;
  meter: MemoryMeter;
  guard: MemoryGuard;
  google: FakeGoogle;
  upstream: FakeUpstream;
  clock: { now: number };
  pending: Promise<unknown>[];
}

export const NOW = Date.UTC(2026, 10, 15, 12, 0, 0);

export function world(env: Record<string, string> = {}): World {
  const clock = { now: NOW };
  const settings = settingsFrom(env);
  const store = new MemoryStore();
  const meter = new MemoryMeter(settings, () => clock.now);
  const guard = new MemoryGuard(settings.dailyBudgetUsd, () => clock.now);
  const google = new FakeGoogle();
  const upstream = new FakeUpstream();
  const pending: Promise<unknown>[] = [];
  const s: Services = {
    settings,
    store,
    meter,
    guard,
    google,
    upstream,
    secret: 'test-secret-test-secret-test-secret',
    rtdnSecret: 'rtdn-secret',
    now: () => clock.now,
    later: (p) => pending.push(p),
  };
  return { s, store, meter, guard, google, upstream, clock, pending };
}

/** A Play Integrity verdict that passes every check for [nonce]. */
export function goodVerdict(nonce: string, now = NOW): IntegrityPayload {
  return {
    requestDetails: { requestPackageName: 'io.zizai.keyboard', nonce, timestampMillis: String(now) },
    appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED', packageName: 'io.zizai.keyboard' },
    deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY'] },
  };
}

export function post(path: string, body: unknown, token?: string): Request {
  const text = JSON.stringify(body);
  return new Request(`https://api.zizaikeyboard.com${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'content-length': String(text.length), ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: text,
  });
}

export function get(path: string, token?: string): Request {
  return new Request(`https://api.zizaikeyboard.com${path}`, { headers: token ? { authorization: `Bearer ${token}` } : {} });
}
