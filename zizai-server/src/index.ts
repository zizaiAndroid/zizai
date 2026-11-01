import { HttpGoogleApi } from './google.ts';
import { NO_STORE, apiError, json } from './http.ts';
import { AccountMeter, GlobalGuard, guardFrom, meterFrom } from './objects.ts';
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
} from './routes.ts';
import type { Services } from './services.ts';
import { settingsFrom } from './settings.ts';
import { D1Store } from './store.ts';
import { GroqUpstream } from './upstream.ts';
import { DELETE_PAGE_HTML } from './delete-page.ts';

/**
 * zizai-server: device accounts, Zizai Pro subscriptions, and a metered Groq proxy for the app's AI
 * cards. ARCHITECTURE.md §6.12 has the design; this file only routes.
 */
export interface Env {
  DB: D1Database;
  METER: DurableObjectNamespace<AccountMeter>;
  GUARD: DurableObjectNamespace<GlobalGuard>;
  /** Secrets. */
  SERVER_SECRET: string;
  GROQ_API_KEY: string;
  FALLBACK_API_KEY?: string;
  GOOGLE_SERVICE_ACCOUNT?: string;
  RTDN_SECRET?: string;
  /** Everything in `settings.ts`, as strings. */
  [setting: string]: unknown;
}

function services(env: Env, ctx: ExecutionContext): Services {
  const settings = settingsFrom(env as unknown as Record<string, string | undefined>);
  return {
    settings,
    store: new D1Store(env.DB),
    meter: meterFrom(env.METER),
    guard: guardFrom(env.GUARD),
    google: env.GOOGLE_SERVICE_ACCOUNT ? new HttpGoogleApi(env.GOOGLE_SERVICE_ACCOUNT, settings.packageName) : null,
    upstream: new GroqUpstream(settings, env.GROQ_API_KEY, env.FALLBACK_API_KEY ?? null),
    secret: env.SERVER_SECRET,
    rtdnSecret: env.RTDN_SECRET ?? null,
    now: () => Date.now(),
    later: (work) => ctx.waitUntil(work),
  };
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';
    const method = request.method;

    if (path === '/' || path === '/health') return json({ service: 'zizai-server', ok: true });
    if ((method === 'GET' || method === 'HEAD') && path === '/delete') {
      return new Response(method === 'HEAD' ? null : DELETE_PAGE_HTML, { headers: { 'content-type': 'text/html; charset=utf-8', ...NO_STORE } });
    }
    if (!env.SERVER_SECRET) return apiError(503, 'The server is not configured.', 'not_configured', 'server_error');

    const s = services(env, ctx);
    if (method === 'GET' && path === '/v1/install/nonce') return handleNonce(s);
    if (method === 'POST' && path === '/v1/install') return handleInstall(request, s);
    if (method === 'GET' && path === '/v1/account') return handleAccount(request, s);
    if (method === 'POST' && path === '/v1/account/delete') return handleDelete(request, s);
    if (method === 'POST' && path === '/v1/account/delete-by-id') return handleDeleteById(request, s);
    if (method === 'POST' && path === '/v1/subscription') return handleSubscription(request, s);
    if (method === 'POST' && path === '/v1/chat/completions') return handleChat(request, s);
    if (method === 'POST' && path === '/v1/audio/transcriptions') return handleTranscription(request, s);
    if (method === 'GET' && path === '/v1/models') return json({ object: 'list', data: [{ id: 'zizai-ai', object: 'model', owned_by: 'zizai' }] });
    if (method === 'POST' && path === '/v1/rtdn') return handleRtdn(request, s);
    return apiError(404, `Unknown endpoint: ${path}`, 'not_found', 'invalid_request_error');
  },

  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    if (!env.SERVER_SECRET) return;
    ctx.waitUntil(
      nightly(services(env, ctx))
        .then(({ rechecked, deleted }) => console.log(`nightly: rechecked ${rechecked} subscriptions, deleted ${deleted} idle accounts`))
        .catch((error) => console.log(`nightly failed: ${String(error)}`)),
    );
  },
} satisfies ExportedHandler<Env>;

export { AccountMeter, GlobalGuard };
