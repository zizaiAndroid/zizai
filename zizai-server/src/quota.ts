import type { Settings } from './settings.ts';

/**
 * The quota rules, as plain functions over plain state, so the Durable Object that runs them stays a
 * thin storage wrapper and the rules can be tested without one.
 *
 * Months are calendar months in UTC. Counting from the subscription date would match Play's billing
 * cycle, but free users have no such date, and one rule for everybody is easier to explain: "30 a
 * month, renewed on the 1st".
 */

export type Plan = 'free' | 'pro';
export type Kind = 'action' | 'audio';

export interface MeterState {
  month: string;
  actions: number;
  audioSeconds: number;
}

export interface Usage {
  month: string;
  actions: number;
  audioSeconds: number;
  /** Null where the plan has no monthly limit worth showing. */
  actionsLimit: number;
  audioSecondsLimit: number;
  /** Pro above fair use: still served, but at [Settings.slowedPerMinute]. */
  slowed: boolean;
  resetsAt: number;
}

export type Decision =
  | { ok: true; usage: Usage }
  | { ok: false; reason: 'quota_exhausted' | 'pro_required' | 'rate_limited'; usage: Usage; retryAfterSeconds?: number };

export function monthOf(now: number): string {
  return new Date(now).toISOString().slice(0, 7);
}

/** Midnight UTC on the first of next month. */
export function nextMonthStart(now: number): number {
  const d = new Date(now);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1);
}

export function emptyState(now: number): MeterState {
  return { month: monthOf(now), actions: 0, audioSeconds: 0 };
}

/** A new month starts from zero; nothing carries over. */
export function rollOver(state: MeterState | undefined, now: number): MeterState {
  if (!state || state.month !== monthOf(now)) return emptyState(now);
  return state;
}

export function usageOf(state: MeterState, plan: Plan, settings: Settings, now: number): Usage {
  const pro = plan === 'pro';
  return {
    month: state.month,
    actions: state.actions,
    audioSeconds: state.audioSeconds,
    actionsLimit: pro ? settings.proActionsPerMonth : settings.freeActionsPerMonth,
    audioSecondsLimit: pro ? settings.proAudioSecondsPerMonth : 0,
    slowed: pro && (state.actions >= settings.proActionsPerMonth || state.audioSeconds >= settings.proAudioSecondsPerMonth),
    resetsAt: nextMonthStart(now),
  };
}

/**
 * Whether one more request of [kind] may go out, and if so, counts it.
 *
 * [hits] are this account's request times in the last minute; the caller keeps them in memory. A
 * refused request is added to them too, so hammering an empty account is limited like anything else.
 *
 * An action is counted here, before the upstream call, and handed back with [refundAction] if the
 * call fails: counting afterwards would let every dropped connection go free. Audio is counted
 * afterwards with [recordAudio], because its length is only known once Groq has heard it; the check
 * here only asks whether the account may transcribe at all.
 */
export function decide(
  state: MeterState,
  hits: number[],
  kind: Kind,
  plan: Plan,
  settings: Settings,
  now: number,
): { decision: Decision; state: MeterState; hits: number[] } {
  const recent = hits.filter((t) => now - t < 60_000);
  const usage = usageOf(state, plan, settings, now);
  const perMinute = plan === 'free' ? settings.freePerMinute : usage.slowed ? settings.slowedPerMinute : settings.proPerMinute;

  if (recent.length >= perMinute) {
    const oldest = Math.min(...recent);
    recent.push(now);
    return {
      decision: { ok: false, reason: 'rate_limited', usage, retryAfterSeconds: Math.max(1, Math.ceil((oldest + 60_000 - now) / 1000)) },
      state,
      hits: recent,
    };
  }
  recent.push(now);

  if (kind === 'audio' && plan !== 'pro') {
    return { decision: { ok: false, reason: 'pro_required', usage }, state, hits: recent };
  }
  if (kind === 'action' && plan === 'free' && state.actions >= settings.freeActionsPerMonth) {
    return { decision: { ok: false, reason: 'quota_exhausted', usage }, state, hits: recent };
  }

  const next = kind === 'action' ? { ...state, actions: state.actions + 1 } : state;
  return { decision: { ok: true, usage: usageOf(next, plan, settings, now) }, state: next, hits: recent };
}

export function refundAction(state: MeterState): MeterState {
  return { ...state, actions: Math.max(0, state.actions - 1) };
}

export function recordAudio(state: MeterState, seconds: number): MeterState {
  return { ...state, audioSeconds: state.audioSeconds + Math.max(0, Math.ceil(seconds)) };
}

/** What a request costs us at Groq, in micro-dollars, for the daily spend ceiling. */
export function chatCostMicros(settings: Settings, tokensIn: number, tokensOut: number): number {
  return Math.ceil((tokensIn * settings.chatInputPricePerM + tokensOut * settings.chatOutputPricePerM));
}

export function audioCostMicros(settings: Settings, seconds: number): number {
  const billed = Math.max(seconds, settings.audioMinBilledSeconds);
  return Math.ceil((billed / 3600) * settings.audioPricePerHour * 1_000_000);
}

/** About four characters per token: only used to turn away absurd inputs and to reserve budget. */
export function estimateTokens(text: string): number {
  return Math.ceil(text.length / 4);
}

/** The day's spend ceiling, as plain state for the global guard. */
export interface GuardState {
  day: string;
  spentMicros: number;
  killed: boolean;
}

export function dayOf(now: number): string {
  return new Date(now).toISOString().slice(0, 10);
}

/**
 * Reserves [estimateMicros] of today's budget, or refuses. The reservation is corrected with the real
 * cost afterwards ([settleSpend]), so the estimate only has to err upwards.
 */
export function reserveSpend(
  state: GuardState | undefined,
  estimateMicros: number,
  budgetUsd: number,
  now: number,
): { ok: boolean; state: GuardState } {
  const day = dayOf(now);
  const current: GuardState = state && state.day === day ? state : { day, spentMicros: 0, killed: state?.killed ?? false };
  if (current.killed) return { ok: false, state: current };
  if (current.spentMicros + estimateMicros > budgetUsd * 1_000_000) return { ok: false, state: current };
  return { ok: true, state: { ...current, spentMicros: current.spentMicros + estimateMicros } };
}

export function settleSpend(state: GuardState | undefined, deltaMicros: number, now: number): GuardState {
  const day = dayOf(now);
  const current: GuardState = state && state.day === day ? state : { day, spentMicros: 0, killed: state?.killed ?? false };
  return { ...current, spentMicros: Math.max(0, current.spentMicros + deltaMicros) };
}
