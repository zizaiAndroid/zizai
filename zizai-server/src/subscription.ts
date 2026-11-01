/**
 * What Google's `purchases.subscriptionsv2` answer means for Pro.
 *
 * Google is the authority on every question here. The expiry stored after this is a cache that lets
 * a request be answered without asking Google each time; every notification and the nightly sweep
 * replace it with Google's current answer.
 */

export interface GoogleSubscription {
  subscriptionState?: string;
  acknowledgementState?: string;
  linkedPurchaseToken?: string;
  testPurchase?: object;
  lineItems?: Array<{
    productId?: string;
    expiryTime?: string;
  }>;
}

export interface SubscriptionFacts {
  productId: string | null;
  state: string;
  /** Latest expiry across the line items, in ms; 0 when Google gave none. */
  expiresAt: number;
  /** Whether Pro applies right now. */
  entitled: boolean;
  needsAcknowledgement: boolean;
  /** The subscription this one replaced (upgrade, resubscribe), which must stop granting Pro. */
  replaces: string | null;
  test: boolean;
}

/** States that grant Pro as long as the paid period has not ended. */
const ENTITLED = new Set([
  'SUBSCRIPTION_STATE_ACTIVE',
  'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
  // Cancelled means "will not renew": the user paid for the current period and keeps it.
  'SUBSCRIPTION_STATE_CANCELED',
]);

export function interpretSubscription(sub: GoogleSubscription, productId: string, now: number): SubscriptionFacts {
  const items = sub.lineItems ?? [];
  const ours = items.filter((item) => item.productId === productId);
  const expiresAt = ours.reduce((latest, item) => {
    const t = Date.parse(item.expiryTime ?? '');
    return Number.isFinite(t) && t > latest ? t : latest;
  }, 0);
  const state = sub.subscriptionState ?? 'SUBSCRIPTION_STATE_UNSPECIFIED';
  return {
    productId: ours.length > 0 ? productId : (items[0]?.productId ?? null),
    state,
    expiresAt,
    entitled: ours.length > 0 && ENTITLED.has(state) && expiresAt > now,
    needsAcknowledgement: sub.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_PENDING',
    replaces: sub.linkedPurchaseToken ?? null,
    test: sub.testPurchase !== undefined,
  };
}

/** RTDN `subscriptionNotification.notificationType` values that only mean "ask Google again". */
export const SUBSCRIPTION_NOTIFICATION_TYPES: Record<number, string> = {
  1: 'RECOVERED',
  2: 'RENEWED',
  3: 'CANCELED',
  4: 'PURCHASED',
  5: 'ON_HOLD',
  6: 'IN_GRACE_PERIOD',
  7: 'RESTARTED',
  8: 'PRICE_CHANGE_CONFIRMED',
  9: 'DEFERRED',
  10: 'PAUSED',
  11: 'PAUSE_SCHEDULE_CHANGED',
  12: 'REVOKED',
  13: 'EXPIRED',
  20: 'PENDING_PURCHASE_CANCELED',
};
