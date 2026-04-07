import {
  TELEMETRY_BUCKET_MINUTES,
  TELEMETRY_SCHEMA_VERSION,
  type TelemetryStoreState,
  type UiErrorInput,
  type UiTelemetryBucketState,
  type UiUsageRollup,
} from './telemetryTypes';
import {
  clearTelemetryStore,
  createAnonymousTelemetryId,
  readTelemetryStore,
  writeTelemetryStore,
} from './telemetryStorage';

export interface TelemetryAggregatorOptions {
  now?: () => Date;
  storage?: Storage;
  release?: string | null;
}

export interface TelemetryAggregator {
  recordCounter: (key: string) => void;
  recordCounterByRoute: (key: string, route: string) => void;
  recordUiError: (input: UiErrorInput) => void;
  collectReadyRollups: () => UiUsageRollup[];
  restoreReadyRollups: (rollups: UiUsageRollup[]) => void;
  getSnapshot: () => UiUsageRollup;
  reset: () => void;
}

interface InMemoryStorage extends Storage {
  _data?: Map<string, string>;
}

function createMemoryStorage(): InMemoryStorage {
  const data = new Map<string, string>();
  return {
    _data: data,
    get length() {
      return data.size;
    },
    clear() {
      data.clear();
    },
    getItem(key: string) {
      return data.get(key) ?? null;
    },
    key(index: number) {
      return Array.from(data.keys())[index] ?? null;
    },
    removeItem(key: string) {
      data.delete(key);
    },
    setItem(key: string, value: string) {
      data.set(key, value);
    },
  };
}

function resolveStorage(storage?: Storage): Storage {
  if (storage != null) {
    return storage;
  }
  if (typeof window !== 'undefined' && window.localStorage != null) {
    return window.localStorage;
  }
  return createMemoryStorage();
}

function alignToBucketStart(value: Date): Date {
  const aligned = new Date(value.getTime());
  aligned.setUTCSeconds(0, 0);
  const minutes = aligned.getUTCMinutes();
  aligned.setUTCMinutes(minutes - (minutes % TELEMETRY_BUCKET_MINUTES));
  return aligned;
}

function addBucketMinutes(start: Date): Date {
  return new Date(start.getTime() + TELEMETRY_BUCKET_MINUTES * 60_000);
}

function createEmptyBucket(at: Date): UiTelemetryBucketState {
  const start = alignToBucketStart(at);
  const end = addBucketMinutes(start);
  return {
    periodStart: start.toISOString(),
    periodEnd: end.toISOString(),
    bucketMinutes: TELEMETRY_BUCKET_MINUTES,
    usage: {
      counters: {},
      byRoute: {},
    },
    errors: {
      groups: [],
    },
  };
}

function hasBucketData(bucket: UiTelemetryBucketState): boolean {
  const counters = Object.keys(bucket.usage.counters).length > 0;
  const routes = Object.values(bucket.usage.byRoute).some((entries) => Object.keys(entries).length > 0);
  return counters || routes || bucket.errors.groups.length > 0;
}

function createFingerprint(input: UiErrorInput): string {
  return [
    input.source,
    input.route ?? 'unknown',
    input.errorName,
    input.message,
  ].join('|');
}

function toRollup(bucket: UiTelemetryBucketState, anonymousId: string, release: string | null): UiUsageRollup {
  return {
    anonymousId,
    schemaVersion: TELEMETRY_SCHEMA_VERSION,
    release,
    periodStart: bucket.periodStart,
    periodEnd: bucket.periodEnd,
    bucketMinutes: bucket.bucketMinutes,
    usage: bucket.usage,
    errors: bucket.errors,
  };
}

function cloneBucket(bucket: UiTelemetryBucketState): UiTelemetryBucketState {
  return {
    periodStart: bucket.periodStart,
    periodEnd: bucket.periodEnd,
    bucketMinutes: bucket.bucketMinutes,
    usage: {
      counters: { ...bucket.usage.counters },
      byRoute: Object.fromEntries(
        Object.entries(bucket.usage.byRoute).map(([key, value]) => [key, { ...value }]),
      ),
    },
    errors: {
      groups: bucket.errors.groups.map((group) => ({ ...group })),
    },
  };
}

function createInitialState(now: Date): TelemetryStoreState {
  return {
    anonymousId: createAnonymousTelemetryId(),
    currentBucket: createEmptyBucket(now),
    readyBuckets: [],
  };
}

export function createTelemetryAggregator(options: TelemetryAggregatorOptions = {}): TelemetryAggregator {
  const now = options.now ?? (() => new Date());
  const storage = resolveStorage(options.storage);
  const release = options.release ?? null;

  let state = readTelemetryStore(storage) ?? createInitialState(now());
  writeTelemetryStore(storage, state);

  function persistState(): void {
    writeTelemetryStore(storage, state);
  }

  function rotateBuckets(reference: Date): void {
    let currentBucketEnd = new Date(state.currentBucket.periodEnd);

    while (reference >= currentBucketEnd) {
      if (hasBucketData(state.currentBucket)) {
        state.readyBuckets.push(cloneBucket(state.currentBucket));
      }
      state.currentBucket = createEmptyBucket(currentBucketEnd);
      currentBucketEnd = new Date(state.currentBucket.periodEnd);
    }

    persistState();
  }

  return {
    recordCounter(key: string) {
      rotateBuckets(now());
      const currentValue = state.currentBucket.usage.counters[key] ?? 0;
      state.currentBucket.usage.counters[key] = currentValue + 1;
      persistState();
    },
    recordCounterByRoute(key: string, route: string) {
      rotateBuckets(now());
      const normalizedRoute = route.length > 0 ? route : 'unknown';
      const bucket = state.currentBucket.usage.byRoute[key] ?? {};
      const currentValue = bucket[normalizedRoute] ?? 0;
      bucket[normalizedRoute] = currentValue + 1;
      state.currentBucket.usage.byRoute[key] = bucket;
      persistState();
    },
    recordUiError(input: UiErrorInput) {
      rotateBuckets(now());
      const fingerprint = createFingerprint(input);
      const currentGroup = state.currentBucket.errors.groups.find((group) => group.fingerprint === fingerprint);
      if (currentGroup != null) {
        currentGroup.count += 1;
        persistState();
        return;
      }
      state.currentBucket.errors.groups.push({
        ...input,
        route: input.route,
        componentStack: input.componentStack ?? null,
        fingerprint,
        count: 1,
      });
      persistState();
    },
    collectReadyRollups() {
      rotateBuckets(now());
      const rollups = state.readyBuckets.map((bucket) => toRollup(bucket, state.anonymousId, release));
      state.readyBuckets = [];
      persistState();
      return rollups;
    },
    restoreReadyRollups(rollups: UiUsageRollup[]) {
      const restored = rollups.map((rollup) => ({
        periodStart: rollup.periodStart,
        periodEnd: rollup.periodEnd,
        bucketMinutes: rollup.bucketMinutes,
        usage: rollup.usage,
        errors: rollup.errors,
      }));
      state.readyBuckets = [...restored, ...state.readyBuckets];
      persistState();
    },
    getSnapshot() {
      rotateBuckets(now());
      return toRollup(cloneBucket(state.currentBucket), state.anonymousId, release);
    },
    reset() {
      state = createInitialState(now());
      clearTelemetryStore(storage);
    },
  };
}
