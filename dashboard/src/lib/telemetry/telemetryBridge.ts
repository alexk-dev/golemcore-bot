import type { TelemetryRecorder, UiErrorInput } from './telemetryTypes';

const NOOP_RECORDER: TelemetryRecorder = {
  recordCounter: () => {},
  recordCounterByRoute: () => {},
  recordUiError: () => {},
};

let activeRecorder: TelemetryRecorder = NOOP_RECORDER;

export function setTelemetryRecorder(recorder: TelemetryRecorder): void {
  activeRecorder = recorder;
}

export function clearTelemetryRecorder(): void {
  activeRecorder = NOOP_RECORDER;
}

export function recordTelemetryCounter(key: string): void {
  activeRecorder.recordCounter(key);
}

export function recordTelemetryCounterByRoute(key: string, route: string): void {
  activeRecorder.recordCounterByRoute(key, route);
}

export function recordTelemetryError(input: UiErrorInput): void {
  activeRecorder.recordUiError(input);
}
