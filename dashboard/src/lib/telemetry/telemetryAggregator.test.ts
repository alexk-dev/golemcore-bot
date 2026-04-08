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

const TELEMETRY_STORAGE_KEY = 'golemcore.dashboard.telemetry.v1';

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

  it('sanitizes dynamic routes and omits raw error details from persisted groups', () => {
    const storage = createMemoryStorage();
    const aggregator = createTelemetryAggregator({
      now: () => new Date('2026-04-06T10:00:00Z'),
      storage,
      release: 'test-release',
    });

    aggregator.recordCounterByRoute('route_view_count', '/sessions/123e4567-e89b-12d3-a456-426614174000');
    aggregator.recordUiError({
      route: '/sessions/123e4567-e89b-12d3-a456-426614174000',
      errorName: 'TypeError',
      message: 'prompt: tell me my secrets',
      source: 'react',
      componentStack: 'at SessionDetailsPage',
    });

    const snapshot = aggregator.getSnapshot();

    expect(snapshot.usage.byRoute.route_view_count).toEqual({
      '/sessions/:id': 1,
    });
    expect(snapshot.errors.groups[0]).toMatchObject({
      route: '/sessions/:id',
      errorName: 'TypeError',
      source: 'react',
      fingerprint: 'react|/sessions/:id|TypeError',
      count: 1,
    });
    expect(snapshot.errors.groups[0]).not.toHaveProperty('message');
    expect(snapshot.errors.groups[0]).not.toHaveProperty('componentStack');
  });

  it('sanitizes previously persisted buckets before they can be flushed', () => {
    const storage = createMemoryStorage();
    storage.setItem(TELEMETRY_STORAGE_KEY, JSON.stringify({
      anonymousId: 'anon-legacy',
      currentBucket: {
        periodStart: '2026-04-06T10:00:00Z',
        periodEnd: '2026-04-06T10:15:00Z',
        bucketMinutes: 15,
        usage: {
          counters: {},
          byRoute: {
            route_view_count: {
              '/sessions/123e4567-e89b-12d3-a456-426614174000': 1,
            },
          },
        },
        errors: {
          groups: [{
            route: '/sessions/123e4567-e89b-12d3-a456-426614174000',
            errorName: 'TypeError',
            source: 'window',
            message: 'secret',
            componentStack: 'at SessionDetailsPage',
            fingerprint: 'window|/sessions/123e4567-e89b-12d3-a456-426614174000|TypeError|secret',
            count: 2,
          }],
        },
      },
      readyBuckets: [],
    }));

    const aggregator = createTelemetryAggregator({
      now: () => new Date('2026-04-06T10:00:00Z'),
      storage,
      release: 'test-release',
    });

    const snapshot = aggregator.getSnapshot();

    expect(snapshot.usage.byRoute.route_view_count).toEqual({
      '/sessions/:id': 1,
    });
    expect(snapshot.errors.groups).toEqual([{
      route: '/sessions/:id',
      errorName: 'TypeError',
      source: 'window',
      fingerprint: 'window|/sessions/:id|TypeError',
      count: 2,
    }]);
  });
});
