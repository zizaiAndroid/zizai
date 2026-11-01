import { hmacHex, issueNonce, newToken, sha256Hex, verifyNonce } from './crypto.ts';
import { ErrorCode, apiError, bearer, json } from './http.ts';
import { judgeIntegrity } from './integrity.ts';
import { audioCostMicros, chatCostMicros, estimateTokens, type Decision, type Plan, type Usage } from './quota.ts';
import type { Services } from './services.ts';
import { interpretSubscription, type SubscriptionFacts } from './subscription.ts';

const MAX_CHAT_BODY_BYTES = 1024 * 1024;

// ----- identity -------------------------------------------------------------------------------------

/** `GET /v1/install/nonce` */
export async function handleNonce(s: Services): Promise<Response> {
  return json({ nonce: await issueNonce(s.secret, s.now()) });
}

/**
 * `POST /v1/install` — find or create this device's account and hand out a token.
 *
 * Body: `{ nonce, integrityToken, deviceHash }`. `deviceHash` is the SHA-256 (hex) of the app-scoped
 * ANDROID_ID; the server keys the account on an HMAC of it, so a reinstall lands on the same account.
 */
export async function handleInstall(request: Request, s: Services): Promise<Response> {
  const body = await readJson<{ nonce?: string; integrityToken?: string; deviceHash?: string }>(request);
  if (!body) return apiError(400, 'Request is not valid JSON.', ErrorCode.BAD_REQUEST);
  const nonce = body.nonce?.trim() ?? '';
  const deviceHash = body.deviceHash?.trim().toLowerCase() ?? '';
  if (!/^[0-9a-f]{64}$/.test(deviceHash)) return apiError(400, 'deviceHash must be 64 hex characters.', ErrorCode.BAD_REQUEST);

  const now = s.now();
  const nonceCheck = await verifyNonce(s.secret, nonce, now, s.settings.nonceTtlMs);
  if (!nonceCheck.ok) return apiError(400, 'The install nonce is not valid. Ask for a new one.', ErrorCode.BAD_NONCE);

  const integrityToken = body.integrityToken?.trim() ?? '';
  if (integrityToken || !s.settings.devAllowUnverifiedInstall) {
    if (!s.google || !integrityToken) {
      return apiError(403, 'This device could not be verified.', ErrorCode.INTEGRITY_FAILED);
    }
    let payload;
    try {
      payload = await s.google.decodeIntegrity(integrityToken);
    } catch {
      return apiError(503, 'Verification is unavailable right now.', ErrorCode.VERIFY_UNAVAILABLE);
    }
    const verdict = judgeIntegrity(payload, {
      packageName: s.settings.packageName,
      nonce,
      allowBasic: s.settings.allowBasicIntegrity,
      now,
      maxAgeMs: s.settings.nonceTtlMs,
    });
    if (!verdict.ok) {
      console.log(`install refused: ${verdict.reason}`);
      return apiError(403, 'This device could not be verified.', ErrorCode.INTEGRITY_FAILED);
    }
  }

  const deviceKey = await hmacHex(s.secret, `device:${deviceHash}`);
  let accountId = await s.store.accountByDevice(deviceKey);
  if (!accountId) {
    accountId = crypto.randomUUID();
    await s.store.createAccount(accountId, deviceKey, now);
  }
  const token = newToken();
  await s.store.addToken(await sha256Hex(token), accountId, now, s.settings.maxTokensPerAccount);

  const plan = await planOf(s, accountId);
  return json({ account_id: accountId, token, ...(await accountView(s, accountId, plan)) });
}

/** `GET /v1/account` */
export async function handleAccount(request: Request, s: Services): Promise<Response> {
  const session = await authenticate(request, s);
  if (!session) return invalidToken();
  const plan = await planOf(s, session.accountId);
  return json({ account_id: session.accountId, ...(await accountView(s, session.accountId, plan)) });
}

/** `POST /v1/account/delete` — with the app's token. */
export async function handleDelete(request: Request, s: Services): Promise<Response> {
  const session = await authenticate(request, s);
  if (!session) return invalidToken();
  await erase(s, session.accountId);
  return json({ deleted: true });
}

/**
 * `POST /v1/account/delete-by-id` — for the web page, after the app is gone. The account id is a
 * random UUID shown in the app's settings; it cannot be guessed, and it opens nothing but this.
 */
export async function handleDeleteById(request: Request, s: Services): Promise<Response> {
  const body = await readJson<{ accountId?: string }>(request);
  const accountId = body?.accountId?.trim().toLowerCase() ?? '';
  if (!/^[0-9a-f-]{36}$/.test(accountId)) return apiError(400, 'That is not an account ID.', ErrorCode.BAD_REQUEST);
  if (!(await s.store.accountExists(accountId))) return apiError(404, 'No account with this ID.', 'not_found');
  await erase(s, accountId);
  return json({ deleted: true });
}

async function erase(s: Services, accountId: string): Promise<void> {
  await s.store.deleteAccount(accountId);
  await s.meter.erase(accountId);
}

// ----- subscriptions --------------------------------------------------------------------------------

/**
 * `POST /v1/subscription` — attach a Play subscription to this device's account.
 *
 * Body: `{ purchaseToken }`. Google decides; the app's word counts for nothing. Safe to repeat: the
 * app posts every subscription Play lists for it on each start, which is also how Pro reaches a new
 * phone.
 */
export async function handleSubscription(request: Request, s: Services): Promise<Response> {
  const session = await authenticate(request, s);
  if (!session) return invalidToken();
  const body = await readJson<{ purchaseToken?: string }>(request);
  const purchaseToken = body?.purchaseToken?.trim() ?? '';
  if (!purchaseToken) return apiError(400, 'purchaseToken is required.', ErrorCode.BAD_REQUEST);
  if (!s.google) return apiError(503, 'Purchase verification is not configured.', ErrorCode.VERIFY_UNAVAILABLE);

  let facts: SubscriptionFacts | null;
  try {
    facts = await refreshSubscription(s, purchaseToken);
  } catch {
    return apiError(503, 'Purchase verification is unavailable right now.', ErrorCode.VERIFY_UNAVAILABLE);
  }
  if (!facts || facts.productId !== s.settings.proProductId) {
    return apiError(400, 'Google does not know this subscription.', ErrorCode.SUBSCRIPTION_INVALID);
  }

  const linked = await s.store.linkedAccounts(purchaseToken);
  if (!linked.includes(session.accountId)) {
    if (linked.length >= s.settings.maxDevicesPerSubscription) {
      return apiError(409, `This subscription is already used on ${linked.length} devices.`, ErrorCode.DEVICE_LIMIT, 'zizai_error', {
        limit: s.settings.maxDevicesPerSubscription,
      });
    }
    await s.store.link(purchaseToken, session.accountId, s.now());
  }

  if (facts.entitled && facts.needsAcknowledgement) {
    // Google refunds a subscription left unacknowledged for three days. The app acknowledges too; this
    // is the safety net for a crash between paying and reaching here.
    const google = s.google;
    s.later(google.acknowledge(s.settings.proProductId, purchaseToken).catch((e) => console.log(`acknowledge failed: ${String(e)}`)));
  }

  const plan = await planOf(s, session.accountId);
  return json({ account_id: session.accountId, entitled: facts.entitled, ...(await accountView(s, session.accountId, plan)) });
}

/** Asks Google about one subscription and stores the answer. Null when Google does not know it. */
export async function refreshSubscription(s: Services, purchaseToken: string): Promise<SubscriptionFacts | null> {
  if (!s.google) return null;
  const now = s.now();
  const answer = await s.google.subscription(purchaseToken);
  if (!answer) {
    const known = await s.store.subscription(purchaseToken);
    if (known) await s.store.saveSubscription({ ...known, state: 'UNKNOWN_TO_GOOGLE', entitled: false, updatedAt: now });
    return null;
  }
  const facts = interpretSubscription(answer, s.settings.proProductId, now);
  await s.store.saveSubscription({
    purchaseToken,
    productId: facts.productId,
    state: facts.state,
    expiresAt: facts.expiresAt,
    entitled: facts.entitled,
    test: facts.test,
    replacedBy: null,
    updatedAt: now,
  });
  if (facts.replaces) {
    // An upgrade or resubscription: the old purchase stops granting Pro, and the devices it served move
    // to the new one so nobody has to restore by hand.
    await s.store.markReplaced(facts.replaces, purchaseToken, now);
    for (const accountId of await s.store.linkedAccounts(facts.replaces)) {
      if ((await s.store.linkedAccounts(purchaseToken)).length >= s.settings.maxDevicesPerSubscription) break;
      await s.store.link(purchaseToken, accountId, now);
    }
  }
  return facts;
}

/**
 * `POST /v1/rtdn?key=…` — Play's real-time developer notifications, pushed by Pub/Sub.
 *
 * Every subscription event is treated the same way: ask Google for the current state. The notification
 * type is only logged. Always answers 200 for anything well-formed, or Pub/Sub retries forever.
 */
export async function handleRtdn(request: Request, s: Services): Promise<Response> {
  const key = new URL(request.url).searchParams.get('key') ?? '';
  if (!s.rtdnSecret || key !== s.rtdnSecret) return new Response('forbidden', { status: 403 });

  const envelope = await readJson<{ message?: { data?: string } }>(request);
  const raw = envelope?.message?.data;
  if (!raw) return json({ ok: true, ignored: 'empty' });

  let notification: {
    packageName?: string;
    subscriptionNotification?: { notificationType?: number; purchaseToken?: string };
    voidedPurchaseNotification?: { purchaseToken?: string; productType?: number };
  };
  try {
    notification = JSON.parse(atob(raw));
  } catch {
    return json({ ok: true, ignored: 'bad_payload' });
  }
  if (notification.packageName && notification.packageName !== s.settings.packageName) {
    return json({ ok: true, ignored: 'other_package' });
  }

  const token = notification.subscriptionNotification?.purchaseToken ?? notification.voidedPurchaseNotification?.purchaseToken;
  if (!token) return json({ ok: true, ignored: 'no_token' });
  try {
    await refreshSubscription(s, token);
  } catch (error) {
    // Google unreachable: answer 500 so Pub/Sub delivers it again later.
    console.log(`rtdn refresh failed: ${String(error)}`);
    return new Response('retry', { status: 500 });
  }
  return json({ ok: true });
}

/** The nightly job: re-ask Google about subscriptions whose cached expiry has passed, and forget idle accounts. */
export async function nightly(s: Services): Promise<{ rechecked: number; deleted: number }> {
  const now = s.now();
  let rechecked = 0;
  for (const token of await s.store.lapsedSubscriptions(now, 200)) {
    try {
      const facts = await refreshSubscription(s, token);
      if (!facts) {
        const known = await s.store.subscription(token);
        if (known) await s.store.saveSubscription({ ...known, entitled: false, updatedAt: now });
      }
      rechecked++;
    } catch (error) {
      console.log(`nightly recheck failed: ${String(error)}`);
    }
  }
  let deleted = 0;
  const cutoff = now - s.settings.inactiveAccountDays * 86_400_000;
  for (const accountId of await s.store.staleAccounts(cutoff, now, 500)) {
    await erase(s, accountId);
    deleted++;
  }
  return { rechecked, deleted };
}

// ----- AI ---------------------------------------------------------------------------------------------

/**
 * `POST /v1/chat/completions` — one AI action, in OpenAI's format, through Groq.
 *
 * The server picks the model and the output limit; the client's choices for either are ignored, or the
 * cost of a request would be the client's to decide.
 */
export async function handleChat(request: Request, s: Services): Promise<Response> {
  const session = await authenticate(request, s);
  if (!session) return invalidToken();

  const declared = Number(request.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > MAX_CHAT_BODY_BYTES) return tooLong();
  const payload = await readJson<{
    messages?: Array<{ role?: string; content?: unknown }>;
    max_tokens?: number;
    max_completion_tokens?: number;
    temperature?: number;
  }>(request);
  if (!payload) return apiError(400, 'Request is not valid JSON.', ErrorCode.BAD_REQUEST);
  const messages = Array.isArray(payload.messages) ? payload.messages : [];
  if (messages.length === 0) return apiError(400, 'Field "messages" is missing.', ErrorCode.BAD_REQUEST);

  const tokensIn = messages.reduce((sum, m) => sum + estimateTokens(textOf(m.content)), 0);
  if (tokensIn > s.settings.maxInputTokens) return tooLong();

  const plan = await planOf(s, session.accountId);
  const decision = await s.meter.take(session.accountId, 'action', plan);
  if (!decision.ok) return refusal(decision, plan, s);

  const maxOut = Math.max(1, Math.min(Number(payload.max_completion_tokens ?? payload.max_tokens ?? s.settings.maxOutputTokens) || s.settings.maxOutputTokens, s.settings.maxOutputTokens));
  const reserved = chatCostMicros(s.settings, tokensIn, maxOut);
  if (!(await s.guard.reserve(reserved))) {
    await s.meter.refundAction(session.accountId);
    return paused();
  }

  const body: Record<string, unknown> = {
    model: s.settings.chatModel,
    messages: messages.map((m) => ({ role: m.role, content: m.content })),
    max_completion_tokens: maxOut,
    reasoning_effort: 'low',
    stream: false,
  };
  if (typeof payload.temperature === 'number' && payload.temperature >= 0 && payload.temperature <= 2) {
    body.temperature = payload.temperature;
  }

  const result = await s.upstream.chat(body);
  if (!result.ok) {
    await s.meter.refundAction(session.accountId);
    await s.guard.settle(-reserved);
    console.log(`chat upstream failed: ${result.status} via ${result.via}`);
    return apiError(502, 'The AI service did not answer. Please try again.', ErrorCode.UPSTREAM_FAILED, 'server_error');
  }

  const usage = parseUsage(result.body);
  await s.guard.settle(chatCostMicros(s.settings, usage.in || tokensIn, usage.out) - reserved);
  return new Response(result.body, {
    status: 200,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', ...usageHeaders(decision.usage) },
  });
}

/**
 * `POST /v1/audio/transcriptions` — cloud voice for Pro, in OpenAI's multipart format, through Groq.
 *
 * Groq is asked for `verbose_json` whatever the client wanted, because only that carries the duration
 * the account is charged; the answer is then turned into the format the client asked for.
 */
export async function handleTranscription(request: Request, s: Services): Promise<Response> {
  const session = await authenticate(request, s);
  if (!session) return invalidToken();

  const declared = Number(request.headers.get('content-length'));
  if (!Number.isFinite(declared) || declared <= 0) return apiError(411, 'A Content-Length is required.', ErrorCode.BAD_REQUEST);
  if (declared > s.settings.maxAudioBytes) return tooLong();

  const plan = await planOf(s, session.accountId);
  const decision = await s.meter.take(session.accountId, 'audio', plan);
  if (!decision.ok) return refusal(decision, plan, s);

  let form: FormData;
  try {
    form = await request.formData();
  } catch {
    return apiError(400, 'Expected multipart/form-data.', ErrorCode.BAD_REQUEST);
  }
  const file = form.get('file');
  if (!file || typeof file === 'string') return apiError(400, 'Field "file" is missing.', ErrorCode.BAD_REQUEST);

  // Worst case for the reservation: compressed speech at about 4 KB a second, capped at two hours.
  const estimateSeconds = Math.min(7200, Math.max(1, declared / 4000));
  const reserved = audioCostMicros(s.settings, estimateSeconds);
  if (!(await s.guard.reserve(reserved))) return paused();

  const upstreamForm = new FormData();
  upstreamForm.append('file', file);
  upstreamForm.append('model', s.settings.transcribeModel);
  upstreamForm.append('response_format', 'verbose_json');
  for (const field of ['language', 'prompt', 'temperature']) {
    const value = form.get(field);
    if (typeof value === 'string' && value) upstreamForm.append(field, value);
  }

  const result = await s.upstream.transcribe(upstreamForm);
  if (!result.ok) {
    await s.guard.settle(-reserved);
    console.log(`transcription upstream failed: ${result.status}`);
    return apiError(502, 'The transcription service did not answer. Please try again.', ErrorCode.UPSTREAM_FAILED, 'server_error');
  }

  let parsed: { text?: string; duration?: number };
  try {
    parsed = JSON.parse(result.body);
  } catch {
    parsed = {};
  }
  const seconds = typeof parsed.duration === 'number' && parsed.duration > 0 ? parsed.duration : estimateSeconds;
  const usage = await s.meter.recordAudio(session.accountId, seconds, plan);
  await s.guard.settle(audioCostMicros(s.settings, seconds) - reserved);

  const format = form.get('response_format');
  const headers = { 'cache-control': 'no-store', ...usageHeaders(usage) };
  if (format === 'text') return new Response(parsed.text ?? '', { headers: { 'content-type': 'text/plain; charset=utf-8', ...headers } });
  if (format === 'verbose_json') return new Response(result.body, { headers: { 'content-type': 'application/json; charset=utf-8', ...headers } });
  return json({ text: parsed.text ?? '' }, 200, usageHeaders(usage));
}

// ----- helpers ----------------------------------------------------------------------------------------

async function authenticate(request: Request, s: Services): Promise<{ accountId: string } | null> {
  const token = bearer(request);
  if (!token) return null;
  const tokenHash = await sha256Hex(token);
  const accountId = await s.store.accountByToken(tokenHash);
  if (!accountId) return null;
  s.later(s.store.touch(accountId, tokenHash, s.now()));
  return { accountId };
}

async function planOf(s: Services, accountId: string): Promise<Plan> {
  return (await s.store.proUntil(accountId, s.now())) > 0 ? 'pro' : 'free';
}

async function accountView(s: Services, accountId: string, plan: Plan) {
  const usage = await s.meter.usage(accountId, plan);
  const proUntil = plan === 'pro' ? await s.store.proUntil(accountId, s.now()) : 0;
  return { plan, pro_until: proUntil || null, usage: usageJson(usage) };
}

function usageJson(usage: Usage) {
  return {
    month: usage.month,
    actions: usage.actions,
    actions_limit: usage.actionsLimit,
    audio_seconds: usage.audioSeconds,
    audio_seconds_limit: usage.audioSecondsLimit,
    slowed: usage.slowed,
    resets_at: usage.resetsAt,
  };
}

function usageHeaders(usage: Usage): Record<string, string> {
  return {
    'x-zizai-actions-used': String(usage.actions),
    'x-zizai-actions-limit': String(usage.actionsLimit),
  };
}

function refusal(decision: Extract<Decision, { ok: false }>, plan: Plan, s: Services): Response {
  const usage = usageJson(decision.usage);
  switch (decision.reason) {
    case 'quota_exhausted':
      return apiError(
        402,
        `Your ${s.settings.freeActionsPerMonth} free AI actions for this month are used up.`,
        ErrorCode.QUOTA_EXHAUSTED,
        'insufficient_quota',
        { usage, plan },
      );
    case 'pro_required':
      return apiError(403, 'Cloud voice is part of Zizai Pro.', ErrorCode.PRO_REQUIRED, 'insufficient_quota', { usage, plan });
    case 'rate_limited': {
      const response = apiError(429, 'Too many requests. Please wait a moment.', ErrorCode.RATE_LIMITED, 'rate_limit_error', { usage, plan });
      response.headers.set('retry-after', String(decision.retryAfterSeconds ?? 30));
      return response;
    }
  }
}

function invalidToken(): Response {
  return apiError(401, 'No valid Zizai token.', ErrorCode.INVALID_TOKEN, 'invalid_request_error');
}

function tooLong(): Response {
  return apiError(413, 'The input is too long for Zizai AI.', ErrorCode.INPUT_TOO_LONG, 'invalid_request_error');
}

function paused(): Response {
  return apiError(503, 'Zizai AI is paused right now. Please try again later.', ErrorCode.SERVICE_PAUSED, 'server_error');
}

async function readJson<T>(request: Request): Promise<T | null> {
  try {
    return (await request.json()) as T;
  } catch {
    return null;
  }
}

function textOf(content: unknown): string {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content.map((part) => (part && typeof part === 'object' && 'text' in part ? String((part as { text: unknown }).text) : '')).join(' ');
  }
  return '';
}

function parseUsage(body: string): { in: number; out: number } {
  try {
    const parsed = JSON.parse(body) as { usage?: { prompt_tokens?: number; completion_tokens?: number } };
    return { in: parsed.usage?.prompt_tokens ?? 0, out: parsed.usage?.completion_tokens ?? 0 };
  } catch {
    return { in: 0, out: 0 };
  }
}
