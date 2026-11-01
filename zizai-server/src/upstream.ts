import type { Settings } from './settings.ts';

/**
 * The model provider: Groq, with an optional OpenAI-compatible fallback for chat.
 *
 * The fallback is only tried when Groq is overloaded or failing (429, 5xx, or no answer at all). A 4xx
 * other than 429 is the request's fault and would fail the same way anywhere.
 */
export interface UpstreamResult {
  ok: boolean;
  status: number;
  body: string;
  /** Which provider answered, for the logs. */
  via: 'groq' | 'fallback' | 'none';
}

export interface Upstream {
  chat(body: Record<string, unknown>): Promise<UpstreamResult>;
  transcribe(form: FormData): Promise<UpstreamResult>;
}

export class GroqUpstream implements Upstream {
  private readonly settings: Settings;
  private readonly apiKey: string;
  private readonly fallbackKey: string | null;
  private readonly fetcher: typeof fetch;

  constructor(settings: Settings, apiKey: string, fallbackKey: string | null, fetcher: typeof fetch = (input, init) => fetch(input, init)) {
    this.settings = settings;
    this.apiKey = apiKey;
    this.fallbackKey = fallbackKey;
    // Wrapped, not stored bare: Workers throw "Illegal invocation" when fetch runs with another `this`.
    this.fetcher = fetcher;
  }

  async chat(body: Record<string, unknown>): Promise<UpstreamResult> {
    const first = await this.post(`${this.settings.groqBaseUrl}/chat/completions`, this.apiKey, JSON.stringify(body), 'groq');
    if (first.ok || !retriable(first.status)) return first;
    const { fallbackBaseUrl, fallbackChatModel } = this.settings;
    if (!fallbackBaseUrl || !fallbackChatModel || !this.fallbackKey) return first;
    // The fallback gets its own model name, and none of Groq's reasoning switches it may not know.
    const { reasoning_effort: _ignored, ...rest } = body;
    return this.post(
      `${fallbackBaseUrl}/chat/completions`,
      this.fallbackKey,
      JSON.stringify({ ...rest, model: fallbackChatModel }),
      'fallback',
    );
  }

  async transcribe(form: FormData): Promise<UpstreamResult> {
    return this.post(`${this.settings.groqBaseUrl}/audio/transcriptions`, this.apiKey, form, 'groq');
  }

  private async post(url: string, key: string, body: string | FormData, via: 'groq' | 'fallback'): Promise<UpstreamResult> {
    try {
      const headers: Record<string, string> = { authorization: `Bearer ${key}` };
      if (typeof body === 'string') headers['content-type'] = 'application/json';
      const response = await this.fetcher(url, { method: 'POST', headers, body });
      return { ok: response.ok, status: response.status, body: await response.text(), via };
    } catch (error) {
      console.log(`${via} unreachable: ${String(error).slice(0, 200)}`);
      return { ok: false, status: 0, body: '', via: 'none' };
    }
  }
}

function retriable(status: number): boolean {
  return status === 0 || status === 429 || status >= 500;
}
