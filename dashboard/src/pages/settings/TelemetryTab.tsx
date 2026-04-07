import { type ReactElement, useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Modal } from 'react-bootstrap';
import toast from 'react-hot-toast';

import type { TelemetryConfig } from '../../api/settings';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import { useUpdateTelemetry } from '../../hooks/useSettings';

interface TelemetryTabProps {
  config: TelemetryConfig;
}

interface DisableTelemetryModalProps {
  show: boolean;
  onKeepEnabled: () => void;
  onDisableAnyway: () => void;
}

function hasTelemetryDiff(current: TelemetryConfig, initial: TelemetryConfig): boolean {
  return current.enabled !== initial.enabled;
}

function DisableTelemetryModal({
  show,
  onKeepEnabled,
  onDisableAnyway,
}: DisableTelemetryModalProps): ReactElement {
  return (
    <Modal show={show} onHide={onKeepEnabled} centered size="sm">
      <Modal.Header closeButton>
        <Modal.Title>Turn off anonymous telemetry?</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <p className="mb-3 text-body-secondary small">
          If you disable this feature, UI errors will no longer be collected, and that can reduce the
          signal used to improve reliability and overall product quality.
        </p>
        <p className="mb-3 text-body-secondary small">
          Open source products have limited resources for quality assurance and product improvement.
          Disabling anonymous telemetry makes it harder to detect issues quickly and can slow down
          product development.
        </p>
        <p className="mb-0 text-body-secondary small">
          Only anonymous, aggregated product statistics and grouped UI error summaries are sent.
        </p>
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" variant="secondary" size="sm" onClick={onDisableAnyway}>
          Disable anyway
        </Button>
        <Button type="button" variant="primary" size="sm" onClick={onKeepEnabled}>
          Keep telemetry enabled
        </Button>
      </Modal.Footer>
    </Modal>
  );
}

export default function TelemetryTab({ config }: TelemetryTabProps): ReactElement {
  const updateTelemetry = useUpdateTelemetry();
  const [form, setForm] = useState<TelemetryConfig>({ ...config });
  const [showDisableConfirm, setShowDisableConfirm] = useState(false);
  const isDirty = hasTelemetryDiff(form, config);

  useEffect(() => {
    // Keep the local draft aligned with the latest runtime config payload.
    setForm({ ...config });
    setShowDisableConfirm(false);
  }, [config]);

  const handleToggle = (checked: boolean): void => {
    if (!checked && form.enabled === true) {
      setShowDisableConfirm(true);
      return;
    }
    setForm({ ...form, enabled: checked });
  };

  const handleSave = async (): Promise<void> => {
    await updateTelemetry.mutateAsync(form);
    toast.success('Telemetry settings saved');
  };

  return (
    <>
      <Card className="settings-card">
        <Card.Body>
          <SettingsCardTitle title="Telemetry" />
          <Alert variant="info" className="mb-3">
            <div className="fw-semibold mb-1">All data is anonymous.</div>
            <div className="small">
              Only aggregated product statistics and grouped UI error summaries are sent to help
              improve the product, reliability, and overall quality.
            </div>
          </Alert>
          <p className="text-body-secondary small mb-3">
            Telemetry stays separate from usage tracking. It is enabled by default and helps us
            understand aggregate interface usage, plugin activity, model and tier usage patterns, and
            grouped UI failures.
          </p>
          <Form.Check
            type="switch"
            label="Enable anonymous telemetry"
            checked={form.enabled === true}
            onChange={(event) => handleToggle(event.target.checked)}
            className="mb-3"
          />
          <SettingsSaveBar>
            <Button
              type="button"
              variant="primary"
              size="sm"
              onClick={() => {
                void handleSave();
              }}
              disabled={!isDirty || updateTelemetry.isPending}
            >
              {updateTelemetry.isPending ? 'Saving...' : 'Save'}
            </Button>
            <SaveStateHint isDirty={isDirty} />
          </SettingsSaveBar>
        </Card.Body>
      </Card>

      <DisableTelemetryModal
        show={showDisableConfirm}
        onKeepEnabled={() => setShowDisableConfirm(false)}
        onDisableAnyway={() => {
          setForm({ ...form, enabled: false });
          setShowDisableConfirm(false);
        }}
      />
    </>
  );
}
