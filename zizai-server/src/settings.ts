/**
 * Everything tunable, read from the Worker's environment with safe defaults.
 *
 * Limits and prices live in `wrangler.jsonc` rather than in code so they can change with a redeploy of
 * the configuration alone, and so the public source does not have to be edited to adjust a quota.
 * Secrets (`GROQ_API_KEY`, `GOOGLE_SERVICE_ACCOUNT`, `RTDN_SECRET`, `SERVER_SECRET`) never appear here.
 */

export interface Settings {
  packageName: string;
  proProductId: string;

  freeActionsPerMonth: number;
  /** Pro fair use: above these the account is slowed to [slowedPerMinute], never blocked. */
  proActionsPerMonth: number;
  proAudioSecondsPerMonth: number;
  freePerMinute: number;
  proPerMinute: number;
  slowedPerMinute: number;
  maxDevicesPerSubscription: number;
  maxTokensPerAccount: number;

  maxInputTokens: number;
  maxOutputTokens: number;
  maxAudioBytes: number;

  groqBaseUrl: string;
  chatModel: string;
  transcribeModel: string;
  fallbackBaseUrl: string | null;
  fallbackChatModel: string | null;

  /** US dollars per million tokens, and per hour of audio; only used to estimate spend. */
  chatInputPricePerM: number;
  chatOutputPricePerM: number;
  audioPricePerHour: number;
  /** Groq bills short transcriptions as if they were this long. */
  audioMinBilledSeconds: number;
  dailyBudgetUsd: number;

  allowBasicIntegrity: boolean;
  /** Local development only: accept installs without a Play Integrity token. Never set in production. */
  devAllowUnverifiedInstall: boolean;
  nonceTtlMs: number;
  /** Accounts unseen this long, with no subscription, are deleted by the nightly job. */
  inactiveAccountDays: number;
}

export type SettingsEnv = Partial<Record<string, string | undefined>>;

export function settingsFrom(env: SettingsEnv): Settings {
  return {
    packageName: str(env.PACKAGE_NAME, 'io.zizai.keyboard'),
    proProductId: str(env.PRO_PRODUCT_ID, 'zizai_pro'),

    freeActionsPerMonth: int(env.FREE_ACTIONS_PER_MONTH, 30),
    proActionsPerMonth: int(env.PRO_ACTIONS_PER_MONTH, 5000),
    proAudioSecondsPerMonth: int(env.PRO_AUDIO_SECONDS_PER_MONTH, 20 * 3600),
    freePerMinute: int(env.FREE_PER_MINUTE, 10),
    proPerMinute: int(env.PRO_PER_MINUTE, 30),
    slowedPerMinute: int(env.SLOWED_PER_MINUTE, 2),
    maxDevicesPerSubscription: int(env.MAX_DEVICES_PER_SUBSCRIPTION, 3),
    maxTokensPerAccount: int(env.MAX_TOKENS_PER_ACCOUNT, 3),

    maxInputTokens: int(env.MAX_INPUT_TOKENS, 8000),
    maxOutputTokens: int(env.MAX_OUTPUT_TOKENS, 1024),
    maxAudioBytes: int(env.MAX_AUDIO_BYTES, 25 * 1024 * 1024),

    groqBaseUrl: str(env.GROQ_BASE_URL, 'https://api.groq.com/openai/v1').replace(/\/+$/, ''),
    chatModel: str(env.CHAT_MODEL, 'openai/gpt-oss-20b'),
    transcribeModel: str(env.TRANSCRIBE_MODEL, 'whisper-large-v3-turbo'),
    fallbackBaseUrl: env.FALLBACK_BASE_URL ? env.FALLBACK_BASE_URL.replace(/\/+$/, '') : null,
    fallbackChatModel: env.FALLBACK_CHAT_MODEL || null,

    chatInputPricePerM: dec(env.CHAT_INPUT_PRICE_PER_M, 0.075),
    chatOutputPricePerM: dec(env.CHAT_OUTPUT_PRICE_PER_M, 0.3),
    audioPricePerHour: dec(env.AUDIO_PRICE_PER_HOUR, 0.04),
    audioMinBilledSeconds: int(env.AUDIO_MIN_BILLED_SECONDS, 10),
    dailyBudgetUsd: dec(env.DAILY_BUDGET_USD, 20),

    allowBasicIntegrity: env.ALLOW_BASIC_INTEGRITY === 'true',
    devAllowUnverifiedInstall: env.DEV_ALLOW_UNVERIFIED_INSTALL === 'true',
    nonceTtlMs: int(env.NONCE_TTL_SECONDS, 600) * 1000,
    inactiveAccountDays: int(env.INACTIVE_ACCOUNT_DAYS, 365),
  };
}

function str(value: string | undefined, fallback: string): string {
  return value && value.trim() ? value.trim() : fallback;
}

function int(value: string | undefined, fallback: number): number {
  const n = Number(value);
  return Number.isInteger(n) && n >= 0 ? n : fallback;
}

function dec(value: string | undefined, fallback: number): number {
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? n : fallback;
}
