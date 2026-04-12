import type { SystemUpdateStatusResponse } from '../../api/system';
import { isValidTimeInput, normalizeIntervalValue, type UpdateSettingsFormState } from './updateSettingsUtils';

export function canSaveUpdateSettings(
  form: UpdateSettingsFormState,
  status: SystemUpdateStatusResponse,
  isSaving: boolean,
): boolean {
  const intervalValid = normalizeIntervalValue(form.checkIntervalMinutes) != null;
  const windowTimesValid = isValidTimeInput(form.maintenanceWindowStartLocal)
    && isValidTimeInput(form.maintenanceWindowEndLocal);
  return status.enabled && !isSaving && intervalValid && (!form.maintenanceWindowEnabled || windowTimesValid);
}
