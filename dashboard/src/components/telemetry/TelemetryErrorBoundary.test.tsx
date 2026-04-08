/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

const recordUiError = vi.hoisted(() => vi.fn());

vi.mock('../../lib/telemetry/TelemetryProvider', () => ({
  useTelemetry: () => ({
    recordUiError,
  }),
}));

import { TelemetryErrorBoundary } from './TelemetryErrorBoundary';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

function Explodes(): never {
  throw new Error('boom');
}

describe('TelemetryErrorBoundary', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    recordUiError.mockClear();
  });

  it('records render errors through the telemetry boundary', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const suppressWindowError = (event: ErrorEvent) => {
      event.preventDefault();
    };

    window.addEventListener('error', suppressWindowError);

    expect(() => {
      act(() => {
        root.render(
          <TelemetryErrorBoundary>
            <Explodes />
          </TelemetryErrorBoundary>,
        );
      });
    }).not.toThrow();

    expect(recordUiError).toHaveBeenCalled();

    act(() => {
      root.unmount();
    });
    window.removeEventListener('error', suppressWindowError);
    consoleError.mockRestore();
  });
});
