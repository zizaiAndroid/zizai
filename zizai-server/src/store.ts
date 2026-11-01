/**
 * The durable records: device accounts, their tokens, and Play subscriptions.
 *
 * What is **not** here is the point of the design: no text, no audio, no IP address, no Google
 * account. An account is a random id, an HMAC of a device hash and two timestamps.
 */

export interface SubscriptionRow {
  purchaseToken: string;
  productId: string | null;
  state: string;
  expiresAt: number;
  entitled: boolean;
  test: boolean;
  replacedBy: string | null;
  updatedAt: number;
}

export interface Store {
  accountByDevice(deviceKey: string): Promise<string | null>;
  createAccount(accountId: string, deviceKey: string, now: number): Promise<void>;
  /** Adds a token and revokes the oldest beyond [keep], so a device's reinstalls cannot pile up. */
  addToken(tokenHash: string, accountId: string, now: number, keep: number): Promise<void>;
  accountByToken(tokenHash: string): Promise<string | null>;
  touch(accountId: string, tokenHash: string, now: number): Promise<void>;
  accountExists(accountId: string): Promise<boolean>;

  /** Latest expiry of a subscription that currently grants this account Pro; 0 when none. */
  proUntil(accountId: string, now: number): Promise<number>;
  subscription(purchaseToken: string): Promise<SubscriptionRow | null>;
  saveSubscription(row: SubscriptionRow): Promise<void>;
  markReplaced(oldToken: string, newToken: string, now: number): Promise<void>;
  linkedAccounts(purchaseToken: string): Promise<string[]>;
  link(purchaseToken: string, accountId: string, now: number): Promise<void>;

  deleteAccount(accountId: string): Promise<void>;
  /** Subscriptions still marked as granting Pro although their cached expiry has passed. */
  lapsedSubscriptions(now: number, limit: number): Promise<string[]>;
  /** Accounts unseen since [cutoff] with no subscription that still grants Pro. */
  staleAccounts(cutoff: number, now: number, limit: number): Promise<string[]>;
}

export class D1Store implements Store {
  private readonly db: D1Database;

  constructor(db: D1Database) {
    this.db = db;
  }

  async accountByDevice(deviceKey: string): Promise<string | null> {
    const row = await this.db.prepare('SELECT id FROM accounts WHERE device_key = ?').bind(deviceKey).first<{ id: string }>();
    return row?.id ?? null;
  }

  async createAccount(accountId: string, deviceKey: string, now: number): Promise<void> {
    await this.db
      .prepare('INSERT INTO accounts (id, device_key, created_at, last_seen_at) VALUES (?, ?, ?, ?)')
      .bind(accountId, deviceKey, now, now)
      .run();
  }

  async addToken(tokenHash: string, accountId: string, now: number, keep: number): Promise<void> {
    await this.db.batch([
      this.db.prepare('INSERT INTO tokens (token_hash, account_id, created_at, last_seen_at) VALUES (?, ?, ?, ?)').bind(tokenHash, accountId, now, now),
      this.db
        .prepare(
          `DELETE FROM tokens WHERE account_id = ? AND token_hash NOT IN (
             SELECT token_hash FROM tokens WHERE account_id = ? ORDER BY created_at DESC LIMIT ?)`,
        )
        .bind(accountId, accountId, keep),
    ]);
  }

  async accountByToken(tokenHash: string): Promise<string | null> {
    const row = await this.db.prepare('SELECT account_id AS id FROM tokens WHERE token_hash = ?').bind(tokenHash).first<{ id: string }>();
    return row?.id ?? null;
  }

  async touch(accountId: string, tokenHash: string, now: number): Promise<void> {
    await this.db.batch([
      this.db.prepare('UPDATE accounts SET last_seen_at = ? WHERE id = ?').bind(now, accountId),
      this.db.prepare('UPDATE tokens SET last_seen_at = ? WHERE token_hash = ?').bind(now, tokenHash),
    ]);
  }

  async accountExists(accountId: string): Promise<boolean> {
    const row = await this.db.prepare('SELECT 1 AS one FROM accounts WHERE id = ?').bind(accountId).first<{ one: number }>();
    return row !== null;
  }

  async proUntil(accountId: string, now: number): Promise<number> {
    const row = await this.db
      .prepare(
        `SELECT MAX(s.expires_at) AS until FROM subscription_accounts sa
           JOIN subscriptions s ON s.purchase_token = sa.purchase_token
          WHERE sa.account_id = ? AND s.entitled = 1 AND s.replaced_by IS NULL AND s.expires_at > ?`,
      )
      .bind(accountId, now)
      .first<{ until: number | null }>();
    return Number(row?.until ?? 0) || 0;
  }

  async subscription(purchaseToken: string): Promise<SubscriptionRow | null> {
    const row = await this.db
      .prepare(
        `SELECT purchase_token AS purchaseToken, product_id AS productId, state, expires_at AS expiresAt,
                entitled, test, replaced_by AS replacedBy, updated_at AS updatedAt
           FROM subscriptions WHERE purchase_token = ?`,
      )
      .bind(purchaseToken)
      .first<Omit<SubscriptionRow, 'entitled' | 'test'> & { entitled: number; test: number }>();
    return row ? { ...row, entitled: row.entitled === 1, test: row.test === 1 } : null;
  }

  async saveSubscription(row: SubscriptionRow): Promise<void> {
    await this.db
      .prepare(
        `INSERT INTO subscriptions (purchase_token, product_id, state, expires_at, entitled, test, replaced_by, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (purchase_token) DO UPDATE SET product_id = excluded.product_id, state = excluded.state,
           expires_at = excluded.expires_at, entitled = excluded.entitled, test = excluded.test,
           updated_at = excluded.updated_at`,
      )
      .bind(row.purchaseToken, row.productId, row.state, row.expiresAt, row.entitled ? 1 : 0, row.test ? 1 : 0, row.replacedBy, row.updatedAt)
      .run();
  }

  async markReplaced(oldToken: string, newToken: string, now: number): Promise<void> {
    await this.db
      .prepare('UPDATE subscriptions SET replaced_by = ?, entitled = 0, updated_at = ? WHERE purchase_token = ?')
      .bind(newToken, now, oldToken)
      .run();
  }

  async linkedAccounts(purchaseToken: string): Promise<string[]> {
    const rows = await this.db
      .prepare('SELECT account_id AS id FROM subscription_accounts WHERE purchase_token = ? ORDER BY linked_at')
      .bind(purchaseToken)
      .all<{ id: string }>();
    return (rows.results ?? []).map((r) => r.id);
  }

  async link(purchaseToken: string, accountId: string, now: number): Promise<void> {
    await this.db
      .prepare('INSERT OR IGNORE INTO subscription_accounts (purchase_token, account_id, linked_at) VALUES (?, ?, ?)')
      .bind(purchaseToken, accountId, now)
      .run();
  }

  async deleteAccount(accountId: string): Promise<void> {
    await this.db.batch([
      this.db.prepare('DELETE FROM tokens WHERE account_id = ?').bind(accountId),
      this.db.prepare('DELETE FROM subscription_accounts WHERE account_id = ?').bind(accountId),
      this.db.prepare('DELETE FROM accounts WHERE id = ?').bind(accountId),
    ]);
  }

  async lapsedSubscriptions(now: number, limit: number): Promise<string[]> {
    const rows = await this.db
      .prepare('SELECT purchase_token AS token FROM subscriptions WHERE entitled = 1 AND expires_at <= ? LIMIT ?')
      .bind(now, limit)
      .all<{ token: string }>();
    return (rows.results ?? []).map((r) => r.token);
  }

  async staleAccounts(cutoff: number, now: number, limit: number): Promise<string[]> {
    const rows = await this.db
      .prepare(
        `SELECT a.id FROM accounts a
          WHERE a.last_seen_at < ?
            AND NOT EXISTS (
              SELECT 1 FROM subscription_accounts sa JOIN subscriptions s ON s.purchase_token = sa.purchase_token
               WHERE sa.account_id = a.id AND s.entitled = 1 AND s.expires_at > ?)
          LIMIT ?`,
      )
      .bind(cutoff, now, limit)
      .all<{ id: string }>();
    return (rows.results ?? []).map((r) => r.id);
  }
}
