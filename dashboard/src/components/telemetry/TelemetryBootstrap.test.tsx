/* @vitest-environment jsdom */

import { act, type ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

const telemetryProviderSpy = vi.hoisted(() => vi.fn(({ children }: { enabled: boolean; flushRollup: unknown; children: ReactNode }) => <>{children}</>));
const runtimeConfigState = vi.hoisted(() => ({
  telemetryEnabled: true,
  telemetryClientId: 'runtime-client-id',
}));

vi.mock('../../hooks/useSettings', () => ({
  useRuntimeConfig: () => ({
    data: {
      telemetry: {
        enabled: runtimeConfigState.telemetryEnabled,
        clientId: runtimeConfigState.telemetryClientId,
      },
    },
  }),
}));

vi.mock('../../lib/telemetry/TelemetryProvider', () => ({
  TelemetryProvider: telemetryProviderSpy,
}));

vi.mock('./TelemetryErrorBoundary', () => ({
  TelemetryErrorBoundary: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

vi.mock('./TelemetryRouteTracker', () => ({
  TelemetryRouteTracker: () => <div data-testid="telemetry-route-tracker" />,
}));

import { TelemetryBootstrap } from './TelemetryBootstrap';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

describe('TelemetryBootstrap', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    runtimeConfigState.telemetryEnabled = true;
    runtimeConfigState.telemetryClientId = 'runtime-client-id';
    vi.unstubAllGlobals();
  });

  it('reads runtimeConfig.telemetry.enabled and starts the provider inside the existing router tree', () => {
    telemetryProviderSpy.mockClear();
    runtimeConfigState.telemetryEnabled = true;

    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(
        <MemoryRouter initialEntries={['/chat']}>
          <TelemetryBootstrap />
        </MemoryRouter>,
      );
    });

    expect(document.body.innerHTML).toContain('telemetry-route-tracker');
    expect(telemetryProviderSpy).toHaveBeenCalled();
    expect(telemetryProviderSpy.mock.calls[0][0].enabled).toBe(true);

    act(() => {
      root.unmount();
    });
  });

  it('configures GA with runtime client id before sending a manual page view', () => {
    const gtagSpy = vi.fn();
    vi.stubGlobal('gtag', gtagSpy);
    telemetryProviderSpy.mockClear();
    runtimeConfigState.telemetryEnabled = true;
    runtimeConfigState.telemetryClientId = 'stable-client-id';

    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(
        <MemoryRouter initialEntries={['/sessions/12345678-1234-4234-9234-123456789abc']}>
          <TelemetryBootstrap />
        </MemoryRouter>,
      );
    });

    expect(gtagSpy).toHaveBeenNthCalledWith(1, 'config', 'G-ZB1YDYV2MB', {
      client_id: 'stable-client-id',
      send_page_view: false,
    });
    expect(gtagSpy).toHaveBeenNthCalledWith(2, 'event', 'page_view', {
      page_location: 'http://localhost:3000/sessions/:id',
      page_title: '',
    });

    act(() => {
      root.unmount();
    });
  });
});
