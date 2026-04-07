export const TELEMETRY_BUCKET_MINUTES = 15 as const;
export const TELEMETRY_SCHEMA_VERSION = 1 as const;

export type UiErrorSource = 'react' | 'window' | 'unhandledrejection';

export interface UiErrorInput {
  route: string | null;
  errorName: string;
  message: string;
  source: UiErrorSource;
  componentStack?: string | null;
}

export interface UiErrorGroup extends UiErrorInput {
  fingerprint: string;
  count: number;
}

export interface UiErrorRollup {
  groups: UiErrorGroup[];
}

export interface UiUsageCounters {
  counters: Record<string, number>;
  byRoute: Record<string, Record<string, number>>;
}

export interface UiTelemetryBucketState {
  periodStart: string;
  periodEnd: string;
  bucketMinutes: typeof TELEMETRY_BUCKET_MINUTES;
  usage: UiUsageCounters;
  errors: UiErrorRollup;
}

export interface UiUsageRollup extends UiTelemetryBucketState {
  anonymousId: string;
  schemaVersion: typeof TELEMETRY_SCHEMA_VERSION;
  release: string | null;
}

export interface TelemetryStoreState {
  anonymousId: string;
  currentBucket: UiTelemetryBucketState;
  readyBuckets: UiTelemetryBucketState[];
}

export interface TelemetryRecorder {
  recordCounter: (key: string) => void;
  recordCounterByRoute: (key: string, route: string) => void;
  recordUiError: (input: UiErrorInput) => void;
}
