import { beforeEach, describe, expect, it, vi } from 'vitest';

interface TelemetryResponse {
  config?: {
    _telemetry?: {
      counterKey?: string;
      value?: string;
    };
  };
}

const responseSuccessHandler = vi.hoisted(() => ({
  current: null as ((value: TelemetryResponse) => TelemetryResponse | Promise<TelemetryResponse>) | null,
}));
const recordTelemetryCounter = vi.hoisted(() => vi.fn());
const recordTelemetryKeyedCounter = vi.hoisted(() => vi.fn());
const fakeClient = vi.hoisted(() => ({
  interceptors: {
    request: {
      use: vi.fn(),
    },
    response: {
      use: vi.fn((onFulfilled: (value: TelemetryResponse) => TelemetryResponse | Promise<TelemetryResponse>) => {
        responseSuccessHandler.current = onFulfilled;
        return 0;
      }),
    },
  },
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));

vi.mock('axios', () => ({
  default: {
    create: vi.fn(() => fakeClient),
    post: vi.fn(),
  },
}));

vi.mock('../lib/telemetry/telemetryBridge', () => ({
  recordTelemetryCounter,
  recordTelemetryKeyedCounter,
}));

vi.mock('../store/authStore', () => ({
  useAuthStore: {
    getState: () => ({
      accessToken: null,
      setAccessToken: vi.fn(),
      logout: vi.fn(),
    }),
  },
}));

import './client';

describe('client telemetry interceptor', () => {
  beforeEach(() => {
    recordTelemetryCounter.mockClear();
    recordTelemetryKeyedCounter.mockClear();
  });

  it('records keyed telemetry counters for successful responses with a dimension value', async () => {
    await responseSuccessHandler.current?.({
      config: {
        _telemetry: {
          counterKey: 'settings_save_count_by_section',
          value: 'telemetry',
        },
      },
    });

    expect(recordTelemetryKeyedCounter).toHaveBeenCalledWith('settings_save_count_by_section', 'telemetry');
    expect(recordTelemetryCounter).not.toHaveBeenCalled();
  });

  it('records plain telemetry counters when the response metadata has no dimension value', async () => {
    await responseSuccessHandler.current?.({
      config: {
        _telemetry: {
          counterKey: 'settings_open_count',
        },
      },
    });

    expect(recordTelemetryCounter).toHaveBeenCalledWith('settings_open_count');
    expect(recordTelemetryKeyedCounter).not.toHaveBeenCalled();
  });
});
