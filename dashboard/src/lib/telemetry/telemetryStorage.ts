import type { TelemetryStoreState } from './telemetryTypes';

const TELEMETRY_STORAGE_KEY = 'golemcore.dashboard.telemetry.v1';

function supportsRandomUuid(): boolean {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function';
}

function generateAnonymousId(): string {
  if (supportsRandomUuid()) {
    return crypto.randomUUID();
  }
  return `anon-${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36)}`;
}

export function createAnonymousTelemetryId(): string {
  return generateAnonymousId();
}

export function readTelemetryStore(storage: Storage): TelemetryStoreState | null {
  const raw = storage.getItem(TELEMETRY_STORAGE_KEY);
  if (raw == null || raw.length === 0) {
    return null;
  }

  try {
    return JSON.parse(raw) as TelemetryStoreState;
  } catch {
    storage.removeItem(TELEMETRY_STORAGE_KEY);
    return null;
  }
}

export function writeTelemetryStore(storage: Storage, state: TelemetryStoreState): void {
  storage.setItem(TELEMETRY_STORAGE_KEY, JSON.stringify(state));
}

export function clearTelemetryStore(storage: Storage): void {
  storage.removeItem(TELEMETRY_STORAGE_KEY);
}
