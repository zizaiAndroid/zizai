import assert from 'node:assert/strict';
import { test } from 'node:test';
import { issueNonce, verifyNonce } from '../src/crypto.ts';
import { judgeIntegrity } from '../src/integrity.ts';
import { audioCostMicros, chatCostMicros, decide, emptyState, monthOf, nextMonthStart, reserveSpend, rollOver } from '../src/quota.ts';
import { settingsFrom } from '../src/settings.ts';
import { interpretSubscription } from '../src/subscription.ts';
import { NOW, goodVerdict } from './fakes.ts';

const settings = settingsFrom({});

test('free plan: 30 actions, then quota_exhausted', () => {
  let state = emptyState(NOW);
  let hits: number[] = [];
  for (let i = 0; i < 30; i++) {
    const r = decide(state, [], 'action', 'free', settings, NOW + i * 61_000);
    assert.equal(r.decision.ok, true, `action ${i + 1}`);
    state = r.state;
    hits = r.hits;
  }
  const refused = decide(state, [], 'action', 'free', settings, NOW + 40 * 61_000);
  assert.equal(refused.decision.ok, false);
  assert.equal(!refused.decision.ok && refused.decision.reason, 'quota_exhausted');
  assert.equal(refused.state.actions, 30);
  assert.ok(hits.length >= 1);
});

test('a new month starts from zero', () => {
  const used = { month: monthOf(NOW), actions: 30, audioSeconds: 0 };
  const next = rollOver(used, nextMonthStart(NOW));
  assert.equal(next.actions, 0);
  assert.notEqual(next.month, used.month);
});

test('rate limit counts refused requests too', () => {
  let hits: number[] = [];
  const state = emptyState(NOW);
  for (let i = 0; i < settings.freePerMinute; i++) hits = decide(state, hits, 'action', 'free', settings, NOW + i).hits;
  const r = decide(state, hits, 'action', 'free', settings, NOW + 100);
  assert.equal(!r.decision.ok && r.decision.reason, 'rate_limited');
  assert.ok(!r.decision.ok && (r.decision.retryAfterSeconds ?? 0) >= 1);
});

test('pro is never blocked by the monthly count, only slowed above fair use', () => {
  const heavy = { month: monthOf(NOW), actions: settings.proActionsPerMonth + 10, audioSeconds: 0 };
  const first = decide(heavy, [], 'action', 'pro', settings, NOW);
  assert.equal(first.decision.ok, true);
  assert.equal(first.decision.usage.slowed, true);
  const second = decide(first.state, first.hits, 'action', 'pro', settings, NOW + 1);
  const third = decide(second.state, second.hits, 'action', 'pro', settings, NOW + 2);
  assert.equal(!third.decision.ok && third.decision.reason, 'rate_limited', 'slowed to 2 a minute');
});

test('audio needs pro', () => {
  const r = decide(emptyState(NOW), [], 'audio', 'free', settings, NOW);
  assert.equal(!r.decision.ok && r.decision.reason, 'pro_required');
  assert.equal(decide(emptyState(NOW), [], 'audio', 'pro', settings, NOW).decision.ok, true);
});

test('costs: a typical AI card is about $0.00035, short audio is billed at 10 s', () => {
  assert.equal(chatCostMicros(settings, 300, 500), Math.ceil(300 * 0.15 + 500 * 0.6));
  assert.equal(audioCostMicros(settings, 3), audioCostMicros(settings, 10));
  assert.equal(audioCostMicros(settings, 3600), 40_000);
});

test('daily spend ceiling refuses past the budget and resets the next day', () => {
  const full = reserveSpend(undefined, 20_000_000, 20, NOW);
  assert.equal(full.ok, true);
  assert.equal(reserveSpend(full.state, 1, 20, NOW).ok, false);
  assert.equal(reserveSpend(full.state, 1, 20, NOW + 86_400_000).ok, true);
  assert.equal(reserveSpend({ ...full.state, killed: true }, 1, 20, NOW + 86_400_000).ok, false, 'the kill switch survives the day');
});

test('nonce: valid, tampered, expired', async () => {
  const nonce = await issueNonce('k', NOW);
  assert.deepEqual(await verifyNonce('k', nonce, NOW + 1000, 600_000), { ok: true });
  assert.equal((await verifyNonce('other', nonce, NOW, 600_000)).ok, false);
  assert.equal((await verifyNonce('k', nonce.replace(/.$/, (c) => (c === 'a' ? 'b' : 'a')), NOW, 600_000)).ok, false);
  assert.deepEqual(await verifyNonce('k', nonce, NOW + 700_000, 600_000), { ok: false, reason: 'expired' });
  assert.match(nonce, /^[A-Za-z0-9_.-]+$/, 'URL-safe, as Play Integrity requires');
});

test('integrity: every field is checked', () => {
  const expected = { packageName: 'io.zizai.keyboard', nonce: 'n', allowBasic: false, now: NOW, maxAgeMs: 600_000 };
  assert.deepEqual(judgeIntegrity(goodVerdict('n'), expected), { ok: true });
  const bad = (patch: (v: ReturnType<typeof goodVerdict>) => void) => {
    const v = goodVerdict('n');
    patch(v);
    return judgeIntegrity(v, expected);
  };
  assert.deepEqual(bad((v) => (v.requestDetails!.requestPackageName = 'com.copy')), { ok: false, reason: 'package' });
  assert.deepEqual(bad((v) => (v.requestDetails!.nonce = 'other')), { ok: false, reason: 'nonce' });
  assert.deepEqual(bad((v) => (v.requestDetails!.timestampMillis = String(NOW - 3_600_000))), { ok: false, reason: 'stale' });
  assert.deepEqual(bad((v) => (v.appIntegrity!.appRecognitionVerdict = 'UNRECOGNIZED_VERSION')), { ok: false, reason: 'app_not_recognized' });
  assert.deepEqual(bad((v) => (v.deviceIntegrity!.deviceRecognitionVerdict = ['MEETS_BASIC_INTEGRITY'])), { ok: false, reason: 'device' });
  const basic = goodVerdict('n');
  basic.deviceIntegrity!.deviceRecognitionVerdict = ['MEETS_BASIC_INTEGRITY'];
  assert.deepEqual(judgeIntegrity(basic, { ...expected, allowBasic: true }), { ok: true });
});

test('subscriptions: which states grant pro', () => {
  const future = new Date(NOW + 86_400_000).toISOString();
  const past = new Date(NOW - 1000).toISOString();
  const sub = (state: string, expiry: string, productId = 'zizai_pro') => ({ subscriptionState: state, lineItems: [{ productId, expiryTime: expiry }] });
  const entitled = (s: object) => interpretSubscription(s, 'zizai_pro', NOW).entitled;

  assert.equal(entitled(sub('SUBSCRIPTION_STATE_ACTIVE', future)), true);
  assert.equal(entitled(sub('SUBSCRIPTION_STATE_IN_GRACE_PERIOD', future)), true);
  assert.equal(entitled(sub('SUBSCRIPTION_STATE_CANCELED', future)), true, 'cancelled but paid up');
  assert.equal(entitled(sub('SUBSCRIPTION_STATE_CANCELED', past)), false);
  assert.equal(entitled(sub('SUBSCRIPTION_STATE_ON_HOLD', future)), false);
  assert.equal(entitled(sub('SUBSCRIPTION_STATE_EXPIRED', past)), false);
  assert.equal(entitled(sub('SUBSCRIPTION_STATE_PENDING', future)), false);
  assert.equal(entitled(sub('SUBSCRIPTION_STATE_ACTIVE', future, 'other_product')), false);

  const facts = interpretSubscription(
    { ...sub('SUBSCRIPTION_STATE_ACTIVE', future), acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING', linkedPurchaseToken: 'old', testPurchase: {} },
    'zizai_pro',
    NOW,
  );
  assert.equal(facts.needsAcknowledgement, true);
  assert.equal(facts.replaces, 'old');
  assert.equal(facts.test, true);
  assert.equal(facts.expiresAt, Date.parse(future));
});
