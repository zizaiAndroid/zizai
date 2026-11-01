import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  handleAccount,
  handleChat,
  handleDelete,
  handleDeleteById,
  handleInstall,
  handleNonce,
  handleRtdn,
  handleSubscription,
  handleTranscription,
  nightly,
} from '../src/routes.ts';
import { NOW, get, goodVerdict, post, world, type World } from './fakes.ts';

const DEVICE = 'a'.repeat(64);

async function install(w: World, device = DEVICE): Promise<{ token: string; accountId: string }> {
  const { nonce } = (await (await handleNonce(w.s)).json()) as { nonce: string };
  w.google.integrity = goodVerdict(nonce, w.clock.now);
  const response = await handleInstall(post('/v1/install', { nonce, integrityToken: 'it', deviceHash: device }), w.s);
  assert.equal(response.status, 200, await response.clone().text());
  const body = (await response.json()) as { token: string; account_id: string };
  return { token: body.token, accountId: body.account_id };
}

function chat(token: string) {
  return post('/v1/chat/completions', { model: 'gpt-4o', max_tokens: 99999, messages: [{ role: 'user', content: 'fix this' }] }, token);
}

function activePro(w: World, token: string, days = 30) {
  w.google.subscriptions.set(token, {
    subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
    acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING',
    lineItems: [{ productId: 'zizai_pro', expiryTime: new Date(w.clock.now + days * 86_400_000).toISOString() }],
  });
}

test('install: verified device gets an account; reinstall finds the same one', async () => {
  const w = world();
  const first = await install(w);
  const second = await install(w);
  assert.equal(first.accountId, second.accountId);
  assert.notEqual(first.token, second.token);
  assert.equal(w.store.accounts.size, 1);
  const other = await install(w, 'b'.repeat(64));
  assert.notEqual(other.accountId, first.accountId);
});

test('install: a failed verdict or a bad nonce is refused', async () => {
  const w = world();
  const { nonce } = (await (await handleNonce(w.s)).json()) as { nonce: string };
  w.google.integrity = { ...goodVerdict(nonce), appIntegrity: { appRecognitionVerdict: 'UNEVALUATED' } };
  let r = await handleInstall(post('/v1/install', { nonce, integrityToken: 'it', deviceHash: DEVICE }), w.s);
  assert.equal(r.status, 403);
  r = await handleInstall(post('/v1/install', { nonce: 'forged.nonce.x', integrityToken: 'it', deviceHash: DEVICE }), w.s);
  assert.equal(r.status, 400);
  r = await handleInstall(post('/v1/install', { nonce, deviceHash: DEVICE }), w.s);
  assert.equal(r.status, 403, 'no integrity token, not in dev mode');
  assert.equal(w.store.accounts.size, 0);
});

test('install: dev mode accepts a missing integrity token, but still checks one that is sent', async () => {
  const w = world({ DEV_ALLOW_UNVERIFIED_INSTALL: 'true' });
  const { nonce } = (await (await handleNonce(w.s)).json()) as { nonce: string };
  const r = await handleInstall(post('/v1/install', { nonce, deviceHash: DEVICE }), w.s);
  assert.equal(r.status, 200);
  w.google.integrity = {};
  const bad = await handleInstall(post('/v1/install', { nonce, integrityToken: 'it', deviceHash: DEVICE }), w.s);
  assert.equal(bad.status, 403);
});

test('free account: 30 AI actions, then 402 quota_exhausted; the server picks model and limits', async () => {
  const w = world();
  const { token } = await install(w);
  for (let i = 0; i < 30; i++) {
    w.clock.now += 61_000;
    const r = await handleChat(chat(token), w.s);
    assert.equal(r.status, 200, `action ${i + 1}`);
  }
  assert.equal(w.upstream.chats[0]!.model, 'openai/gpt-oss-120b');
  assert.equal(w.upstream.chats[0]!.max_completion_tokens, 1024);
  assert.equal(w.upstream.chats[0]!.reasoning_effort, 'low');

  w.clock.now += 61_000;
  const refused = await handleChat(chat(token), w.s);
  assert.equal(refused.status, 402);
  const body = (await refused.json()) as { error: { code: string }; usage: { actions: number; actions_limit: number } };
  assert.equal(body.error.code, 'quota_exhausted');
  assert.deepEqual([body.usage.actions, body.usage.actions_limit], [30, 30]);
  assert.equal(w.upstream.chats.length, 30, 'the refused request never reached Groq');
});

test('a failed upstream call gives the action back', async () => {
  const w = world();
  const { token, accountId } = await install(w);
  w.upstream.chatAnswer = { ok: false, status: 503, body: '', via: 'groq' };
  assert.equal((await handleChat(chat(token), w.s)).status, 502);
  assert.equal((await w.meter.usage(accountId, 'free')).actions, 0);
  assert.equal(w.guard.state?.spentMicros, 0);
});

test('over the daily budget: 503 and the action is not counted', async () => {
  const w = world({ DAILY_BUDGET_USD: '0' });
  const { token, accountId } = await install(w);
  const r = await handleChat(chat(token), w.s);
  assert.equal(r.status, 503);
  assert.equal((await w.meter.usage(accountId, 'free')).actions, 0);
});

test('no token or a wrong one: 401', async () => {
  const w = world();
  assert.equal((await handleChat(chat('zz_nope'), w.s)).status, 401);
  assert.equal((await handleAccount(get('/v1/account'), w.s)).status, 401);
});

test('subscription: verified with Google, acknowledged, makes the account pro', async () => {
  const w = world();
  const { token, accountId } = await install(w);
  activePro(w, 'purchase-1');
  const r = await handleSubscription(post('/v1/subscription', { purchaseToken: 'purchase-1' }, token), w.s);
  assert.equal(r.status, 200, await r.clone().text());
  const body = (await r.json()) as { plan: string; pro_until: number; usage: { actions_limit: number } };
  assert.equal(body.plan, 'pro');
  assert.equal(body.usage.actions_limit, 5000);
  await Promise.all(w.pending);
  assert.deepEqual(w.google.acknowledged, ['purchase-1']);

  for (let i = 0; i < 40; i++) {
    w.clock.now += 3_000;
    assert.equal((await handleChat(chat(token), w.s)).status, 200);
  }
  assert.equal((await w.meter.usage(accountId, 'pro')).actions, 40);
});

test('subscription: unknown token, wrong product, Google down', async () => {
  const w = world();
  const { token } = await install(w);
  assert.equal((await handleSubscription(post('/v1/subscription', { purchaseToken: 'made-up' }, token), w.s)).status, 400);
  w.google.subscriptions.set('other', { subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE', lineItems: [{ productId: 'something_else', expiryTime: new Date(NOW + 1e9).toISOString() }] });
  assert.equal((await handleSubscription(post('/v1/subscription', { purchaseToken: 'other' }, token), w.s)).status, 400);
  w.google.down = true;
  assert.equal((await handleSubscription(post('/v1/subscription', { purchaseToken: 'x' }, token), w.s)).status, 503);
});

test('subscription: at most 3 devices', async () => {
  const w = world();
  activePro(w, 'shared');
  for (const d of ['1', '2', '3']) {
    const { token } = await install(w, d.repeat(64));
    assert.equal((await handleSubscription(post('/v1/subscription', { purchaseToken: 'shared' }, token), w.s)).status, 200);
  }
  const { token } = await install(w, '4'.repeat(64));
  const r = await handleSubscription(post('/v1/subscription', { purchaseToken: 'shared' }, token), w.s);
  assert.equal(r.status, 409);
  assert.equal(((await r.json()) as { error: { code: string } }).error.code, 'device_limit');
});

test('rtdn: a revocation ends pro at once; a bad secret is refused', async () => {
  const w = world();
  const { token } = await install(w);
  activePro(w, 'p');
  await handleSubscription(post('/v1/subscription', { purchaseToken: 'p' }, token), w.s);
  assert.equal(((await (await handleAccount(get('/v1/account', token), w.s)).json()) as { plan: string }).plan, 'pro');

  w.google.subscriptions.set('p', { subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED', lineItems: [{ productId: 'zizai_pro', expiryTime: new Date(w.clock.now - 1).toISOString() }] });
  const data = btoa(JSON.stringify({ packageName: 'io.zizai.keyboard', subscriptionNotification: { notificationType: 12, purchaseToken: 'p' } }));
  assert.equal((await handleRtdn(post('/v1/rtdn?key=wrong', { message: { data } }), w.s)).status, 403);
  assert.equal((await handleRtdn(post('/v1/rtdn?key=rtdn-secret', { message: { data } }), w.s)).status, 200);
  assert.equal(((await (await handleAccount(get('/v1/account', token), w.s)).json()) as { plan: string }).plan, 'free');
});

test('a resubscription moves the devices to the new purchase', async () => {
  const w = world();
  const { token, accountId } = await install(w);
  activePro(w, 'old');
  await handleSubscription(post('/v1/subscription', { purchaseToken: 'old' }, token), w.s);
  activePro(w, 'new');
  w.google.subscriptions.get('new')!.linkedPurchaseToken = 'old';
  const data = btoa(JSON.stringify({ subscriptionNotification: { notificationType: 4, purchaseToken: 'new' } }));
  await handleRtdn(post('/v1/rtdn?key=rtdn-secret', { message: { data } }), w.s);
  assert.deepEqual(await w.store.linkedAccounts('new'), [accountId]);
  assert.equal(w.store.subs.get('old')!.entitled, false);
  assert.ok((await w.store.proUntil(accountId, w.clock.now)) > 0);
});

test('nightly: a lapsed subscription is re-checked; idle free accounts are deleted', async () => {
  const w = world();
  const { token, accountId } = await install(w);
  activePro(w, 'p', 1);
  await handleSubscription(post('/v1/subscription', { purchaseToken: 'p' }, token), w.s);
  w.clock.now += 2 * 86_400_000;
  w.google.subscriptions.set('p', { subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED', lineItems: [{ productId: 'zizai_pro', expiryTime: new Date(w.clock.now - 86_400_000).toISOString() }] });
  const first = await nightly(w.s);
  assert.equal(first.rechecked, 1);
  assert.equal(w.store.subs.get('p')!.entitled, false);

  w.clock.now += 400 * 86_400_000;
  const second = await nightly(w.s);
  assert.equal(second.deleted, 1);
  assert.equal(await w.store.accountExists(accountId), false);
});

test('transcription: pro only, charged by the duration Groq reports, answered in the asked format', async () => {
  const w = world();
  const { token, accountId } = await install(w);
  const form = () => {
    const f = new FormData();
    f.append('file', new Blob([new Uint8Array(48_000)], { type: 'audio/wav' }), 'a.wav');
    f.append('model', 'whisper-1');
    f.append('response_format', 'text');
    return new Request('https://api.zizaikeyboard.com/v1/audio/transcriptions', { method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-length': '48500' }, body: f });
  };
  const refused = await handleTranscription(form(), w.s);
  assert.equal(refused.status, 403);
  assert.equal(((await refused.json()) as { error: { code: string } }).error.code, 'pro_required');

  activePro(w, 'p');
  await handleSubscription(post('/v1/subscription', { purchaseToken: 'p' }, token), w.s);
  const r = await handleTranscription(form(), w.s);
  assert.equal(r.status, 200);
  assert.equal(await r.text(), '你好');
  const sent = w.upstream.transcriptions[0]!;
  assert.equal(sent.get('model'), 'whisper-large-v3-turbo');
  assert.equal(sent.get('response_format'), 'verbose_json');
  assert.equal((await w.meter.usage(accountId, 'pro')).audioSeconds, 13);
});

test('delete: with the token, or with the account ID from the web page', async () => {
  const w = world();
  const a = await install(w);
  assert.equal((await handleDelete(post('/v1/account/delete', {}, a.token), w.s)).status, 200);
  assert.equal(await w.store.accountExists(a.accountId), false);
  assert.equal((await handleChat(chat(a.token), w.s)).status, 401);

  const b = await install(w, 'c'.repeat(64));
  assert.equal((await handleDeleteById(post('/v1/account/delete-by-id', { accountId: 'not-an-id' }), w.s)).status, 400);
  assert.equal((await handleDeleteById(post('/v1/account/delete-by-id', { accountId: b.accountId }), w.s)).status, 200);
  assert.equal(await w.store.accountExists(b.accountId), false);
});
