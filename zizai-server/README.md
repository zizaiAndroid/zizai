# zizai-server

The server behind **Zizai AI** and **Zizai Pro** in the 字在 Zizai keyboard: anonymous device accounts,
Play subscription checks, and a metered proxy to Groq for the AI cards. A single Cloudflare Worker with a
D1 database and two Durable Objects. The design is in [ARCHITECTURE.md §6.12](../docs/ARCHITECTURE.md);
decision 11 and ADR-9 explain why it exists.

You never need it to use Zizai: typing, offline dictation and AI with your own API key don't touch it.

## What it stores, and what it doesn't

Stored: a random account id, an HMAC of a hash of the app-scoped Android ID, SHA-256 hashes of bearer
tokens, last-seen times, Play subscription tokens with their state and expiry, and monthly counters.
Never stored: text, audio, IP addresses, names, emails or Google account identifiers. `src/store.ts`
and `schema.sql` are the whole list.

## Layout

```
src/
  index.ts        router and nightly job
  routes.ts       every endpoint
  quota.ts        plans, monthly counters, rate limits, spend ceiling (pure functions)
  integrity.ts    Play Integrity verdict checks (pure)
  subscription.ts what Google's subscription answer means (pure)
  google.ts       Play Integrity and Play Developer API calls
  upstream.ts     Groq, with an optional fallback for chat
  objects.ts      the Durable Objects: per-account meter, global spend guard
  store.ts        D1 queries
test/             node --test, with in-memory fakes for D1, the objects, Google and Groq
```

## Develop

Requires Node 22 or newer.

```sh
npm install
npm test
npm run typecheck
```

Local server (`wrangler dev`): copy `wrangler.example.jsonc` to `wrangler.jsonc`, and create `.dev.vars`
with `SERVER_SECRET`, `GROQ_API_KEY` and `DEV_ALLOW_UNVERIFIED_INSTALL=true`. That last one lets a debug
build install without Play Integrity; it must never be set in production. Then:

```sh
npm run db:local
npm run dev
```

## Deploy

1. Copy `wrangler.example.jsonc` to `wrangler.jsonc` and fill in the D1 database id
   (`npx wrangler d1 create zizai-server`).
2. Set the secrets: `SERVER_SECRET`, `GROQ_API_KEY`, `GOOGLE_SERVICE_ACCOUNT`, `RTDN_SECRET`
   (`npx wrangler secret put NAME`).
3. `npm run db:remote`, then `npm run deploy`.
4. Point Play's real-time developer notifications (a Pub/Sub push subscription) at
   `https://api.zizaikeyboard.com/v1/rtdn?key=<RTDN_SECRET>`.

The service account needs, in Play Console, "View financial data" and "Manage orders and subscriptions",
and the Play Integrity API enabled in its Google Cloud project.
