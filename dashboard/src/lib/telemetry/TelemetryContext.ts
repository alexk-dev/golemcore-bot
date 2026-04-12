import { createContext, useContext } from 'react';

import type { TelemetryRecorder } from './telemetryTypes';

export const NOOP_RECORDER: TelemetryRecorder = {
  recordCounter: () => {},
  recordKeyedCounter: () => {},
  recordCounterByRoute: () => {},
  recordUiError: () => {},
};

export const TelemetryContext = createContext<TelemetryRecorder>(NOOP_RECORDER);

export function useTelemetry(): TelemetryRecorder {
  return useContext(TelemetryContext);
}
