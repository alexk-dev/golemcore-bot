import type { ReactElement } from 'react';
import { Alert, Button } from 'react-bootstrap';

import type { SystemUpdateStatusResponse } from '../../api/system';
import { formatUpdateTimestamp } from '../../utils/systemUpdateUi';
import { canSaveUpdateSettings } from './updateSettingsValidation';
import type { UpdateSettingsFormState } from './updateSettingsUtils';

interface UpdateSettingsSummaryProps {
  form: UpdateSettingsFormState;
  status: SystemUpdateStatusResponse;
  localTimezone: string;
  saveSummary: string;
}

interface UpdateSettingsActionsProps {
  form: UpdateSettingsFormState;
  status: SystemUpdateStatusResponse;
  isSaving: boolean;
  isDirty: boolean;
  onSave: () => void;
}

export function UpdateSettingsSummary({
  status,
  localTimezone,
  saveSummary,
}: UpdateSettingsSummaryProps): ReactElement {
  return (
    <Alert variant="secondary" className="mb-3 small">
      <div>Your timezone: {localTimezone}</div>
      <div>Saved on server as {saveSummary}</div>
      {status.state === 'WAITING_FOR_WINDOW' && status.nextEligibleAt != null && <div>Next eligible window: {formatUpdateTimestamp(status.nextEligibleAt)}</div>}
      {status.state === 'WAITING_FOR_IDLE' && status.blockedReason != null && <div>Current blocker: {status.blockedReason}</div>}
    </Alert>
  );
}

export function UpdateSettingsActions({
  form,
  status,
  isSaving,
  isDirty,
  onSave,
}: UpdateSettingsActionsProps): ReactElement {
  return (
    <div className="d-flex gap-2">
      <Button type="button" size="sm" variant="primary" disabled={!canSaveUpdateSettings(form, status, isSaving) || !isDirty} onClick={onSave}>
        {isSaving ? 'Saving...' : 'Save settings'}
      </Button>
      {!status.enabled && <span className="small text-body-secondary align-self-center">Backend update feature is disabled, so settings are read-only.</span>}
    </div>
  );
}
