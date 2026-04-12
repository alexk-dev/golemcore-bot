import type { SystemUpdateConfigResponse } from '../../api/system';

const DEFAULT_LOCAL_TIME = '00:00';
const TIME_INPUT_PATTERN = /^([01]\d|2[0-3]):([0-5]\d)$/;

export interface UpdateSettingsFormState {
  autoEnabled: boolean;
  checkIntervalMinutes: string;
  maintenanceWindowEnabled: boolean;
  maintenanceWindowStartLocal: string;
  maintenanceWindowEndLocal: string;
}

function padTime(value: number): string {
  return value.toString().padStart(2, '0');
}

export function isValidTimeInput(value: string): boolean {
  return TIME_INPUT_PATTERN.test(value.trim());
}

export function utcToLocalTimeInput(value: string | null | undefined): string {
  if (!value || !isValidTimeInput(value)) {
    return DEFAULT_LOCAL_TIME;
  }
  const [hours, minutes] = value.split(':').map((part) => Number(part));
  const date = new Date();
  date.setUTCHours(hours, minutes, 0, 0);
  return `${padTime(date.getHours())}:${padTime(date.getMinutes())}`;
}

export function localTimeToUtc(value: string): string {
  if (!isValidTimeInput(value)) {
    return DEFAULT_LOCAL_TIME;
  }
  const [hours, minutes] = value.split(':').map((part) => Number(part));
  const date = new Date();
  date.setHours(hours, minutes, 0, 0);
  return `${padTime(date.getUTCHours())}:${padTime(date.getUTCMinutes())}`;
}

export function configToForm(config: SystemUpdateConfigResponse): UpdateSettingsFormState {
  return {
    autoEnabled: config.autoEnabled,
    checkIntervalMinutes: String(config.checkIntervalMinutes),
    maintenanceWindowEnabled: config.maintenanceWindowEnabled,
    maintenanceWindowStartLocal: utcToLocalTimeInput(config.maintenanceWindowStartUtc),
    maintenanceWindowEndLocal: utcToLocalTimeInput(config.maintenanceWindowEndUtc),
  };
}

export function normalizeIntervalValue(value: string): number | null {
  const parsed = Number.parseInt(value.trim(), 10);
  if (!Number.isFinite(parsed) || parsed < 1) {
    return null;
  }
  return parsed;
}
