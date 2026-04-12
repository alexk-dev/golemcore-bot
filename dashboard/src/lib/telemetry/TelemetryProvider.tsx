import {
  type ReactElement,
  type ReactNode,
  useEffect,
  useMemo,
  useRef,
} from 'react';

import { createTelemetryAggregator, type TelemetryAggregator } from './telemetryAggregator';
import { clearTelemetryRecorder, setTelemetryRecorder } from './telemetryBridge';
import {
  sanitizeTelemetryErrorName,
  sanitizeTelemetryRoute,
} from './telemetrySanitizers';
import { NOOP_RECORDER, TelemetryContext } from './TelemetryContext';
import type { TelemetryRecorder, UiErrorInput, UiUsageRollup } from './telemetryTypes';

export type FlushRollupFn = (rollup: UiUsageRollup) => Promise<void>;

interface TelemetryProviderProps {
  enabled: boolean;
  flushRollup: FlushRollupFn;
  children: ReactNode;
}

function normalizeUnknownError(reason: unknown): { errorName: string } {
  if (reason instanceof Error) {
    return {
      errorName: reason.name,
    };
  }
  return {
    errorName: 'UnhandledRejection',
  };
}

function buildWindowErrorPayload(event: ErrorEvent): UiErrorInput {
  return {
    route: sanitizeTelemetryRoute(window.location.pathname),
    errorName: sanitizeTelemetryErrorName(event.error instanceof Error ? event.error.name : 'WindowError'),
    source: 'window',
  };
}

function buildRejectionPayload(event: PromiseRejectionEvent): UiErrorInput {
  const normalized = normalizeUnknownError(event.reason);
  return {
    route: sanitizeTelemetryRoute(window.location.pathname),
    errorName: sanitizeTelemetryErrorName(normalized.errorName),
    source: 'unhandledrejection',
  };
}

export function TelemetryProvider({ enabled, flushRollup, children }: TelemetryProviderProps): ReactElement {
  const aggregatorRef = useRef<TelemetryAggregator | null>(null);

  if (aggregatorRef.current == null) {
    aggregatorRef.current = createTelemetryAggregator();
  }

  const recorder: TelemetryRecorder = useMemo(() => ({
    recordCounter(key: string) {
      aggregatorRef.current?.recordCounter(key);
    },
    recordKeyedCounter(key: string, value: string) {
      aggregatorRef.current?.recordKeyedCounter(key, value);
    },
    recordCounterByRoute(key: string, route: string) {
      aggregatorRef.current?.recordCounterByRoute(key, route);
    },
    recordUiError(input: UiErrorInput) {
      aggregatorRef.current?.recordUiError(input);
    },
  }), []);

  // Sync the bridge recorder when enabled state changes.
  useEffect(() => {
    if (!enabled) {
      aggregatorRef.current?.reset();
      clearTelemetryRecorder();
      return;
    }

    setTelemetryRecorder(recorder);
    return () => {
      clearTelemetryRecorder();
    };
  }, [enabled, recorder]);

  // Periodically flush completed rollup buckets to the backend.
  useEffect(() => {
    if (!enabled) {
      return;
    }

    async function flushReadyRollups(): Promise<void> {
      const rollups = aggregatorRef.current?.collectReadyRollups() ?? [];
      if (rollups.length === 0) {
        return;
      }

      let nextRollupIndex = 0;
      try {
        for (; nextRollupIndex < rollups.length; nextRollupIndex += 1) {
          await flushRollup(rollups[nextRollupIndex]);
        }
      } catch (error) {
        console.error('Failed to send telemetry rollups', error);
        aggregatorRef.current?.restoreReadyRollups(rollups.slice(nextRollupIndex));
      }
    }

    void flushReadyRollups();

    function handleVisibilityChange(): void {
      if (document.visibilityState === 'visible') {
        void flushReadyRollups();
      }
    }

    const intervalId = window.setInterval(() => {
      void flushReadyRollups();
    }, 60_000);

    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => {
      window.clearInterval(intervalId);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [enabled, flushRollup]);

  // Capture uncaught window errors and promise rejections.
  useEffect(() => {
    if (!enabled) {
      return;
    }

    function handleWindowError(event: ErrorEvent): void {
      recorder.recordUiError(buildWindowErrorPayload(event));
    }

    function handleUnhandledRejection(event: PromiseRejectionEvent): void {
      recorder.recordUiError(buildRejectionPayload(event));
    }

    window.addEventListener('error', handleWindowError);
    window.addEventListener('unhandledrejection', handleUnhandledRejection);
    return () => {
      window.removeEventListener('error', handleWindowError);
      window.removeEventListener('unhandledrejection', handleUnhandledRejection);
    };
  }, [enabled, recorder]);

  return (
    <TelemetryContext.Provider value={enabled ? recorder : NOOP_RECORDER}>
      {children}
    </TelemetryContext.Provider>
  );
}
