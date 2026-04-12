import type { ReactElement } from 'react';
import { Form } from 'react-bootstrap';

import type { SystemUpdateStatusResponse } from '../../api/system';
import { isValidTimeInput, normalizeIntervalValue, type UpdateSettingsFormState } from './updateSettingsUtils';

interface UpdateSettingsFormFieldsProps {
  status: SystemUpdateStatusResponse;
  form: UpdateSettingsFormState;
  isSaving: boolean;
  localTimezone: string;
  onChange: (updater: (current: UpdateSettingsFormState) => UpdateSettingsFormState) => void;
}

export function UpdateSettingsFormFields({
  status,
  form,
  isSaving,
  localTimezone,
  onChange,
}: UpdateSettingsFormFieldsProps): ReactElement {
  const intervalValid = normalizeIntervalValue(form.checkIntervalMinutes) != null;
  return (
    <>
      <Form.Group className="mb-3">
        <Form.Check
          type="switch"
          label="Enable automatic updates"
          checked={form.autoEnabled}
          disabled={!status.enabled || isSaving}
          onChange={(event) => onChange((current) => ({ ...current, autoEnabled: event.target.checked }))}
        />
      </Form.Group>
      <Form.Group className="mb-3">
        <Form.Label>Check interval (minutes)</Form.Label>
        <Form.Control
          size="sm"
          value={form.checkIntervalMinutes}
          disabled={!status.enabled || isSaving}
          isInvalid={!intervalValid}
          onChange={(event) => onChange((current) => ({ ...current, checkIntervalMinutes: event.target.value }))}
        />
        <Form.Text className={!intervalValid ? 'text-danger' : 'text-body-secondary'}>
          Default is 60 minutes. Auto checks do not bypass maintenance window or runtime activity blocking.
        </Form.Text>
      </Form.Group>
      <Form.Group className="mb-3">
        <Form.Check
          type="switch"
          label="Use maintenance window"
          checked={form.maintenanceWindowEnabled}
          disabled={!status.enabled || isSaving}
          onChange={(event) => onChange((current) => ({ ...current, maintenanceWindowEnabled: event.target.checked }))}
        />
        <Form.Text className="text-body-secondary">Disabled means updates may apply at any time once no work is running.</Form.Text>
      </Form.Group>
      <WindowTimeFields form={form} status={status} isSaving={isSaving} localTimezone={localTimezone} onChange={onChange} />
    </>
  );
}

interface WindowTimeFieldsProps {
  form: UpdateSettingsFormState;
  status: SystemUpdateStatusResponse;
  isSaving: boolean;
  localTimezone: string;
  onChange: (updater: (current: UpdateSettingsFormState) => UpdateSettingsFormState) => void;
}

function WindowTimeFields({ form, status, isSaving, localTimezone, onChange }: WindowTimeFieldsProps): ReactElement {
  const disabled = !status.enabled || isSaving || !form.maintenanceWindowEnabled;
  return (
    <div className="row g-3 mb-3">
      <div className="col-md-6">
        <Form.Group>
          <Form.Label>Window start ({localTimezone})</Form.Label>
          <Form.Control
            size="sm"
            value={form.maintenanceWindowStartLocal}
            disabled={disabled}
            isInvalid={form.maintenanceWindowEnabled && !isValidTimeInput(form.maintenanceWindowStartLocal)}
            onChange={(event) => onChange((current) => ({ ...current, maintenanceWindowStartLocal: event.target.value }))}
            placeholder="HH:mm"
          />
        </Form.Group>
      </div>
      <div className="col-md-6">
        <Form.Group>
          <Form.Label>Window end ({localTimezone})</Form.Label>
          <Form.Control
            size="sm"
            value={form.maintenanceWindowEndLocal}
            disabled={disabled}
            isInvalid={form.maintenanceWindowEnabled && !isValidTimeInput(form.maintenanceWindowEndLocal)}
            onChange={(event) => onChange((current) => ({ ...current, maintenanceWindowEndLocal: event.target.value }))}
            placeholder="HH:mm"
          />
        </Form.Group>
      </div>
    </div>
  );
}
