import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateHive } from '../../hooks/useSettings';
import type { HiveConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

interface HiveTabProps {
  config: HiveConfig;
}

export default function HiveTab({ config }: HiveTabProps): ReactElement {
  const updateHive = useUpdateHive();
  const [form, setForm] = useState<HiveConfig>({ ...config });
  const isManaged = form.managedByProperties === true;
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    // Keep the local form synchronized with the latest runtime-config snapshot after query refreshes.
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateHive.mutateAsync(form);
    toast.success('Hive settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle
          title="Hive"
          tip="Configure the control-plane endpoint and bot identity values that Hive uses during enrollment and reconnect flows."
        />

        {isManaged && (
          <Alert variant="warning">
            Hive settings are managed by `bot.hive.*` bootstrap properties. Update application properties instead of the dashboard.
          </Alert>
        )}

        <Form.Check
          type="switch"
          label={<>Enable Hive integration <HelpTip text="Turns on the Hive integration runtime surface for this bot." /></>}
          checked={form.enabled ?? false}
          onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
          disabled={isManaged}
          className="mb-3"
        />

        <Row className="g-3 mb-3">
          <Col md={6}>
            <Form.Group controlId="hive-server-url">
              <Form.Label className="small fw-medium">
                Hive Server URL <HelpTip text="Base HTTP URL for the Hive control plane, for example https://hive.example.com." />
              </Form.Label>
              <Form.Control
                type="url"
                size="sm"
                value={form.serverUrl ?? ''}
                onChange={(event) => setForm({ ...form, serverUrl: event.target.value || null })}
                disabled={isManaged}
                placeholder="https://hive.example.com"
              />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group controlId="hive-display-name">
              <Form.Label className="small fw-medium">
                Display Name <HelpTip text="Human-readable bot name shown in Hive fleet views." />
              </Form.Label>
              <Form.Control
                type="text"
                size="sm"
                value={form.displayName ?? ''}
                onChange={(event) => setForm({ ...form, displayName: event.target.value || null })}
                disabled={isManaged}
                placeholder="Build Runner"
              />
            </Form.Group>
          </Col>
        </Row>

        <Row className="g-3 mb-3">
          <Col md={6}>
            <Form.Group controlId="hive-host-label">
              <Form.Label className="small fw-medium">
                Host Label <HelpTip text="Optional machine label surfaced in Hive for assignment and diagnostics." />
              </Form.Label>
              <Form.Control
                type="text"
                size="sm"
                value={form.hostLabel ?? ''}
                onChange={(event) => setForm({ ...form, hostLabel: event.target.value || null })}
                disabled={isManaged}
                placeholder="builder-lab-a"
              />
            </Form.Group>
          </Col>
          <Col md={6} className="d-flex align-items-end">
            <Form.Check
              type="switch"
              label={<>Auto-connect on startup <HelpTip text="When enabled, the bot should attempt Hive reconnect/join automatically during startup flows." /></>}
              checked={form.autoConnect ?? false}
              onChange={(event) => setForm({ ...form, autoConnect: event.target.checked })}
              disabled={isManaged}
            />
          </Col>
        </Row>

        <SettingsSaveBar>
          <Button
            type="button"
            variant="primary"
            size="sm"
            onClick={() => { void handleSave(); }}
            disabled={isManaged || !isDirty || updateHive.isPending}
          >
            {updateHive.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={!isManaged && isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
