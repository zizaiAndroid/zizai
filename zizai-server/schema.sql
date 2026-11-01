-- zizai-server, a fresh database. Apply with `npm run db:remote` (or db:local for wrangler dev).
-- No text, no audio, no IP addresses and no Google account identifiers are stored anywhere.

-- One anonymous account per device. device_key is HMAC(SERVER_SECRET, SHA-256 of the app-scoped
-- ANDROID_ID), so a reinstall finds the same account and its monthly allowance.
CREATE TABLE IF NOT EXISTS accounts (
  id            TEXT PRIMARY KEY,
  device_key    TEXT NOT NULL UNIQUE,
  created_at    INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL
);

-- Bearer tokens, as SHA-256 only. At most MAX_TOKENS_PER_ACCOUNT per account.
CREATE TABLE IF NOT EXISTS tokens (
  token_hash    TEXT PRIMARY KEY,
  account_id    TEXT NOT NULL,
  created_at    INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS tokens_account ON tokens (account_id);

-- Play subscriptions, as Google last described them. expires_at and entitled are a cache: every
-- notification and the nightly sweep replace them with Google's current answer.
CREATE TABLE IF NOT EXISTS subscriptions (
  purchase_token  TEXT PRIMARY KEY,
  product_id      TEXT,
  state           TEXT NOT NULL,
  expires_at      INTEGER NOT NULL,
  entitled        INTEGER NOT NULL,
  test            INTEGER NOT NULL DEFAULT 0,
  replaced_by     TEXT,
  updated_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS subscriptions_lapse ON subscriptions (entitled, expires_at);

-- Which device accounts a subscription serves (MAX_DEVICES_PER_SUBSCRIPTION at most).
CREATE TABLE IF NOT EXISTS subscription_accounts (
  purchase_token  TEXT NOT NULL,
  account_id      TEXT NOT NULL,
  linked_at       INTEGER NOT NULL,
  PRIMARY KEY (purchase_token, account_id)
);
CREATE INDEX IF NOT EXISTS subscription_accounts_account ON subscription_accounts (account_id);
