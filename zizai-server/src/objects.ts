import { DurableObject } from 'cloudflare:workers';
import type { Guard, Meter } from './meter.ts';
import {
  decide,
  type Decision,
  type GuardState,
  type Kind,
  type MeterState,
  type Plan,
  type Usage,
  recordAudio,
  refundAction,
  reserveSpend,
  rollOver,
  settleSpend,
  usageOf,
} from './quota.ts';
import { settingsFrom, type SettingsEnv } from './settings.ts';

/**
 * One account's monthly counters. Only numbers are stored; the request times for the per-minute limit
 * stay in memory, because a burst is over by the time an idle object is evicted.
 */
export class AccountMeter extends DurableObject<SettingsEnv> {
  private hits: number[] = [];

  private async state(now: number): Promise<MeterState> {
    return rollOver(await this.ctx.storage.get<MeterState>('state'), now);
  }

  async take(kind: Kind, plan: Plan): Promise<Decision> {
    const now = Date.now();
    const result = decide(await this.state(now), this.hits, kind, plan, settingsFrom(this.env), now);
    this.hits = result.hits;
    await this.ctx.storage.put('state', result.state);
    return result.decision;
  }

  async refundAction(): Promise<void> {
    const now = Date.now();
    await this.ctx.storage.put('state', refundAction(await this.state(now)));
  }

  async recordAudio(seconds: number, plan: Plan): Promise<Usage> {
    const now = Date.now();
    const next = recordAudio(await this.state(now), seconds);
    await this.ctx.storage.put('state', next);
    return usageOf(next, plan, settingsFrom(this.env), now);
  }

  async usage(plan: Plan): Promise<Usage> {
    const now = Date.now();
    return usageOf(await this.state(now), plan, settingsFrom(this.env), now);
  }

  async erase(): Promise<void> {
    this.hits = [];
    await this.ctx.storage.deleteAll();
  }
}

/** The day's estimated Groq spend, and the kill switch. One instance for the whole service. */
export class GlobalGuard extends DurableObject<SettingsEnv> {
  async reserve(estimateMicros: number): Promise<boolean> {
    const now = Date.now();
    const result = reserveSpend(await this.ctx.storage.get<GuardState>('state'), estimateMicros, settingsFrom(this.env).dailyBudgetUsd, now);
    await this.ctx.storage.put('state', result.state);
    return result.ok;
  }

  async settle(deltaMicros: number): Promise<void> {
    await this.ctx.storage.put('state', settleSpend(await this.ctx.storage.get<GuardState>('state'), deltaMicros, Date.now()));
  }

  async setKilled(killed: boolean): Promise<GuardState> {
    const current = settleSpend(await this.ctx.storage.get<GuardState>('state'), 0, Date.now());
    const next = { ...current, killed };
    await this.ctx.storage.put('state', next);
    return next;
  }

  async status(): Promise<GuardState> {
    return settleSpend(await this.ctx.storage.get<GuardState>('state'), 0, Date.now());
  }
}

/** Adapters from the interfaces in `meter.ts` to the objects above. */
export function meterFrom(namespace: DurableObjectNamespace<AccountMeter>): Meter {
  const stub = (id: string) => namespace.get(namespace.idFromName(id));
  return {
    take: (id, kind, plan) => stub(id).take(kind, plan),
    refundAction: (id) => stub(id).refundAction(),
    recordAudio: (id, seconds, plan) => stub(id).recordAudio(seconds, plan),
    usage: (id, plan) => stub(id).usage(plan),
    erase: (id) => stub(id).erase(),
  };
}

export function guardFrom(namespace: DurableObjectNamespace<GlobalGuard>): Guard {
  const stub = () => namespace.get(namespace.idFromName('global'));
  return {
    reserve: (micros) => stub().reserve(micros),
    settle: (delta) => stub().settle(delta),
  };
}
