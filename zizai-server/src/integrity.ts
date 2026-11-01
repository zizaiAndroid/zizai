/**
 * Judging a decoded Play Integrity verdict.
 *
 * The verdict only says something useful if every field is checked: a token from another app, for
 * another nonce, or from a sideloaded copy is a perfectly valid token. Each refusal names its reason
 * so a failed install can be told apart in the logs; the app only ever sees `integrity_failed`.
 */

export interface IntegrityPayload {
  requestDetails?: {
    requestPackageName?: string;
    nonce?: string;
    timestampMillis?: string;
  };
  appIntegrity?: {
    appRecognitionVerdict?: string;
    packageName?: string;
  };
  deviceIntegrity?: {
    deviceRecognitionVerdict?: string[];
  };
}

export type IntegrityVerdict =
  | { ok: true }
  | { ok: false; reason: 'package' | 'nonce' | 'stale' | 'app_not_recognized' | 'device' };

export function judgeIntegrity(
  payload: IntegrityPayload,
  expected: { packageName: string; nonce: string; allowBasic: boolean; now: number; maxAgeMs: number },
): IntegrityVerdict {
  const request = payload.requestDetails ?? {};
  if (request.requestPackageName !== expected.packageName) return { ok: false, reason: 'package' };
  if (request.nonce !== expected.nonce) return { ok: false, reason: 'nonce' };

  const at = Number(request.timestampMillis);
  if (!Number.isFinite(at) || Math.abs(expected.now - at) > expected.maxAgeMs) return { ok: false, reason: 'stale' };

  const app = payload.appIntegrity ?? {};
  if (app.appRecognitionVerdict !== 'PLAY_RECOGNIZED' || app.packageName !== expected.packageName) {
    return { ok: false, reason: 'app_not_recognized' };
  }

  const device = payload.deviceIntegrity?.deviceRecognitionVerdict ?? [];
  const strong = device.includes('MEETS_DEVICE_INTEGRITY') || device.includes('MEETS_STRONG_INTEGRITY');
  const basic = device.includes('MEETS_BASIC_INTEGRITY');
  if (!strong && !(expected.allowBasic && basic)) return { ok: false, reason: 'device' };

  return { ok: true };
}
