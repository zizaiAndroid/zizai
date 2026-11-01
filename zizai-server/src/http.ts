/** Response shapes shared by every route. */

/** Nothing here may be cached: a quota or a transcript is only true for one request. */
export const NO_STORE = { 'cache-control': 'no-store' } as const;

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', ...NO_STORE, ...headers },
  });
}

/**
 * Errors in OpenAI's shape. The app's `OpenAiCompatibleClient` reads `error.code` and `error.message`
 * from exactly this structure, so a different shape would surface as "unknown error".
 */
export function apiError(
  status: number,
  message: string,
  code: string,
  type = 'zizai_error',
  extra: Record<string, unknown> = {},
): Response {
  return json({ error: { message, type, code }, ...extra }, status);
}

/** The token from `Authorization: Bearer …`, or null. */
export function bearer(request: Request): string | null {
  const header = request.headers.get('authorization') ?? '';
  if (!header.toLowerCase().startsWith('bearer ')) return null;
  const token = header.slice(7).trim();
  return token || null;
}

/** Error codes the app reacts to. Kept in one place so the app's copy has one thing to match. */
export const ErrorCode = {
  INVALID_TOKEN: 'invalid_token',
  QUOTA_EXHAUSTED: 'quota_exhausted',
  PRO_REQUIRED: 'pro_required',
  RATE_LIMITED: 'rate_limited',
  SERVICE_PAUSED: 'service_paused',
  INTEGRITY_FAILED: 'integrity_failed',
  BAD_NONCE: 'bad_nonce',
  DEVICE_LIMIT: 'device_limit',
  SUBSCRIPTION_INVALID: 'subscription_invalid',
  VERIFY_UNAVAILABLE: 'verify_unavailable',
  INPUT_TOO_LONG: 'input_too_long',
  UPSTREAM_FAILED: 'upstream_failed',
  BAD_REQUEST: 'bad_request',
} as const;
