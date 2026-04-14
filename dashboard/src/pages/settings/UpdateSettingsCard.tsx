import type { ReactElement } from 'react';
import { Alert, Button, Card, Spinner } from '../../components/ui/tailwind-components';

import type { SystemUpdateConfigResponse, SystemUpdateStatusResponse } from '../../api/system';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import {
  localTimeToUtc,
  type UpdateSettingsFormState,
} from './updateSettingsUtils';
import { UpdateSettingsActions, UpdateSettingsSummary } from './UpdateSettingsActions';
import { UpdateSettingsFormFields } from './UpdateSettingsFormFields';

interface UpdateSettingsCardProps {
  status: SystemUpdateStatusResponse;
  config: SystemUpdateConfigResponse | null;
  form: UpdateSettingsFormState | null;
  isLoading: boolean;
  isSaving: boolean;
  onRetry: () => void;
  onChange: (updater: (current: UpdateSettingsFormState) => UpdateSettingsFormState) => void;
  onSave: () => void;
}

function resolveSaveSummary(form: UpdateSettingsFormState, serverTimezone: string | null | undefined): string {
  const timezone = serverTimezone ?? 'UTC';
  if (!form.maintenanceWindowEnabled) {
    return `Any time (${timezone})`;
  }
  return `${localTimeToUtc(form.maintenanceWindowStartLocal)}-${localTimeToUtc(form.maintenanceWindowEndLocal)} ${timezone}`;
}

function isFormDirty(form: UpdateSettingsFormState, config: SystemUpdateConfigResponse): boolean {
  return form.autoEnabled !== config.autoEnabled
    || form.checkIntervalMinutes !== String(config.checkIntervalMinutes)
    || form.maintenanceWindowEnabled !== config.maintenanceWindowEnabled
    || localTimeToUtc(form.maintenanceWindowStartLocal) !== config.maintenanceWindowStartUtc
    || localTimeToUtc(form.maintenanceWindowEndLocal) !== config.maintenanceWindowEndUtc;
}

export function UpdateSettingsCard({
  status,
  config,
  form,
  isLoading,
  isSaving,
  onRetry,
  onChange,
  onSave,
}: UpdateSettingsCardProps): ReactElement {
  const localTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

  if (isLoading || form == null) {
    return (
      <Card className="settings-card updates-card mt-3">
        <Card.Body className="d-flex align-items-center gap-2">
          <Spinner size="sm" animation="border" />
          <span className="small text-body-secondary">Loading auto update settings...</span>
        </Card.Body>
      </Card>
    );
  }

  if (config == null) {
    return (
      <Card className="settings-card updates-card mt-3">
        <Card.Body>
          <SettingsCardTitle title="Auto Update Settings" />
          <Alert variant="warning" className="mb-3">Unable to load update settings from backend.</Alert>
          <Button type="button" size="sm" variant="secondary" onClick={onRetry}>Retry</Button>
        </Card.Body>
      </Card>
    );
  }

  const isDirty = isFormDirty(form, config);

  return (
    <Card className="settings-card updates-card mt-3">
      <Card.Body>
        <SettingsCardTitle title="Auto Update Settings" tip="Edit auto update policy in your local browser time. The backend stores the maintenance window in UTC." />
        <UpdateSettingsFormFields
          status={status}
          form={form}
          isSaving={isSaving}
          localTimezone={localTimezone}
          onChange={onChange}
        />
        <UpdateSettingsSummary
          form={form}
          status={status}
          localTimezone={localTimezone}
          saveSummary={resolveSaveSummary(form, status.serverTimezone)}
        />
        <UpdateSettingsActions form={form} status={status} isSaving={isSaving} isDirty={isDirty} onSave={onSave} />
      </Card.Body>
    </Card>
  );
}
