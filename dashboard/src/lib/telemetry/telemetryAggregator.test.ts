import { describe, expect, it } from 'vitest';

import { createTelemetryAggregator } from './telemetryAggregator';

function createMemoryStorage(): Storage {
  const store = new Map<string, string>();

  return {
    get length() {
      return store.size;
    },
    clear() {
      store.clear();
    },
    getItem(key: string) {
      return store.get(key) ?? null;
    },
    key(index: number) {
      return Array.from(store.keys())[index] ?? null;
    },
    removeItem(key: string) {
      store.delete(key);
    },
    setItem(key: string, value: string) {
      store.set(key, value);
    },
  };
}

describe('telemetryAggregator', () => {
  it('groups UI errors by fingerprint within a 15 minute bucket', () => {
    const storage = createMemoryStorage();
    const aggregator = createTelemetryAggregator({
      now: () => new Date('2026-04-06T10:00:00Z'),
      storage,
      release: 'test-release',
    });

    aggregator.recordUiError({
      route: '/chat',
      errorName: 'TypeError',
      message: 'boom',
      source: 'react',
    });
    aggregator.recordUiError({
      route: '/chat',
      errorName: 'TypeError',
      message: 'boom',
      source: 'react',
    });

    const pendingRollups = aggregator.collectReadyRollups();

    expect(pendingRollups).toHaveLength(0);

    const snapshot = aggregator.getSnapshot();

    expect(snapshot.errors.groups).toHaveLength(1);
    expect(snapshot.errors.groups[0].count).toBe(2);
    expect(snapshot.errors.groups[0].fingerprint).toContain('TypeError');
    expect(snapshot.bucketMinutes).toBe(15);
  });
});
