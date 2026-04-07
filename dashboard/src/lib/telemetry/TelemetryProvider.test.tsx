/* @vitest-environment jsdom */

import { act, type ReactElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { UiUsageRollup } from './telemetryTypes';

const rollupA: UiUsageRollup = {
  anonymousId: 'anon-1',
  schemaVersion: 1,
  release: 'test',
  periodStart: '2026-04-06T10:00:00Z',
  periodEnd: '2026-04-06T10:15:00Z',
  bucketMinutes: 15,
  usage: {
    counters: { settings_open_count: 1 },
    byRoute: {},
  },
  errors: {
    groups: [],
  },
};

const rollupB: UiUsageRollup = {
  anonymousId: 'anon-1',
  schemaVersion: 1,
  release: 'test',
  periodStart: '2026-04-06T10:15:00Z',
  periodEnd: '2026-04-06T10:30:00Z',
  bucketMinutes: 15,
  usage: {
    counters: { route_view_count: 4 },
    byRoute: {},
  },
  errors: {
    groups: [],
  },
};

const aggregatorStub = vi.hoisted(() => ({
  recordCounter: vi.fn(),
  recordKeyedCounter: vi.fn(),
  recordCounterByRoute: vi.fn(),
  recordUiError: vi.fn(),
  collectReadyRollups: vi.fn(() => [rollupA, rollupB]),
  restoreReadyRollups: vi.fn(),
  getSnapshot: vi.fn(() => rollupA),
  reset: vi.fn(),
}));

const postTelemetryRollup = vi.hoisted(() => vi.fn());

vi.mock('./telemetryAggregator', () => ({
  createTelemetryAggregator: () => aggregatorStub,
}));

vi.mock('../../api/telemetry', () => ({
  postTelemetryRollup,
}));

import { TelemetryProvider } from './TelemetryProvider';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

function TestChild(): ReactElement {
  return <div data-testid="telemetry-child">child</div>;
}

async function flushPromises(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe('TelemetryProvider', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    vi.clearAllMocks();
  });

  it('restores only the unsent rollup tail when a later upload fails', async () => {
    postTelemetryRollup
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('network'));

    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    await act(async () => {
      root.render(
        <TelemetryProvider enabled>
          <TestChild />
        </TelemetryProvider>,
      );
      await flushPromises();
    });

    expect(postTelemetryRollup).toHaveBeenNthCalledWith(1, rollupA);
    expect(postTelemetryRollup).toHaveBeenNthCalledWith(2, rollupB);
    expect(aggregatorStub.restoreReadyRollups).toHaveBeenCalledWith([rollupB]);

    await act(async () => {
      root.unmount();
      await flushPromises();
    });

    consoleError.mockRestore();
  });
});
