import {
  createContext,
  type ReactElement,
  type ReactNode,
  useContext,
  useEffect,
  useRef,
} from 'react';

import { postTelemetryRollup } from '../../api/telemetry';
import { createTelemetryAggregator, type TelemetryAggregator } from './telemetryAggregator';
import { clearTelemetryRecorder, setTelemetryRecorder } from './telemetryBridge';
import {
  sanitizeTelemetryErrorName,
  sanitizeTelemetryRoute,
} from './telemetrySanitizers';
import type { TelemetryRecorder, UiErrorInput } from './telemetryTypes';

interface TelemetryProviderProps {
  enabled: boolean;
  children: ReactNode;
}

const NOOP_RECORDER: TelemetryRecorder = {
  recordCounter: () => {},
  recordKeyedCounter: () => {},
  recordCounterByRoute: () => {},
  recordUiError: () => {},
};

const TelemetryContext = createContext<TelemetryRecorder>(NOOP_RECORDER);

function normalizeUnknownError(reason: unknown): { errorName: string } {
  if (reason instanceof Error) {
    return {
      errorName: reason.name,
    };
  }
  if (typeof reason === 'string') {
    return {
      errorName: 'UnhandledRejection',
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

export function TelemetryProvider({ enabled, children }: TelemetryProviderProps): ReactElement {
  const aggregatorRef = useRef<TelemetryAggregator | null>(null);

  if (aggregatorRef.current == null) {
    aggregatorRef.current = createTelemetryAggregator();
  }

  const recorder: TelemetryRecorder = {
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
  };

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

  useEffect(() => {
    if (!enabled) {
      return;
    }

    // Flush completed 15-minute buckets on a light interval and when the page becomes visible again.
    async function flushReadyRollups(): Promise<void> {
      const rollups = aggregatorRef.current?.collectReadyRollups() ?? [];
      if (rollups.length === 0) {
        return;
      }

      let nextRollupIndex = 0;
      try {
        for (; nextRollupIndex < rollups.length; nextRollupIndex += 1) {
          await postTelemetryRollup(rollups[nextRollupIndex]);
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
  }, [enabled]);

  useEffect(() => {
    if (!enabled) {
      return;
    }

    // Capture uncaught window errors and promise rejections into the aggregated UI error bucket.
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

export function useTelemetry(): TelemetryRecorder {
  return useContext(TelemetryContext);
}
