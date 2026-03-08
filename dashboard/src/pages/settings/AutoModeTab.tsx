import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateAuto } from '../../hooks/useSettings';
import type { AutoModeConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

interface AutoModeTabProps {
  config: AutoModeConfig;
}

export default function AutoModeTab({ config }: AutoModeTabProps): ReactElement {
  const updateAuto = useUpdateAuto();
  const [form, setForm] = useState<AutoModeConfig>({ ...config });
  const isAutoDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateAuto.mutateAsync({ ...form, tickIntervalSeconds: 1 });
    toast.success('Auto mode settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle
          title="Auto Mode"
          tip="Autonomous mode where the bot works on goals independently, checking in periodically"
        />
        <Form.Check type="switch" label="Enable Auto Mode" checked={form.enabled ?? true}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })} className="mb-3" />

        <Row className="g-3 mb-3">
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Task Time Limit (minutes) <HelpTip text="Maximum time a single autonomous task can run before being stopped" />
              </Form.Label>
              <Form.Control size="sm" type="number" value={form.taskTimeLimitMinutes ?? 10}
                onChange={(e) => setForm({ ...form, taskTimeLimitMinutes: toNullableInt(e.target.value) })} />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max Goals <HelpTip text="Maximum number of concurrent goals the bot can work on" />
              </Form.Label>
              <Form.Control size="sm" type="number" value={form.maxGoals ?? 3}
                onChange={(e) => setForm({ ...form, maxGoals: toNullableInt(e.target.value) })} />
            </Form.Group>
          </Col>
        </Row>

        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            Model Tier <HelpTip text="Which model tier to use for autonomous tasks" />
          </Form.Label>
          <Form.Select size="sm" value={form.modelTier ?? 'default'} onChange={(e) => setForm({ ...form, modelTier: e.target.value })}>
            <option value="default">Default</option>
            <option value="balanced">Balanced</option>
            <option value="smart">Smart</option>
            <option value="coding">Coding</option>
            <option value="deep">Deep</option>
          </Form.Select>
        </Form.Group>

        <Form.Check type="switch"
          label={<>Auto-start on startup <HelpTip text="Start autonomous mode automatically when the application boots" /></>}
          checked={form.autoStart ?? true}
          onChange={(e) => setForm({ ...form, autoStart: e.target.checked })} className="mb-2" />
        <Form.Check type="switch"
          label={<>Notify milestones <HelpTip text="Send notifications when goals or tasks are completed" /></>}
          checked={form.notifyMilestones ?? true}
          onChange={(e) => setForm({ ...form, notifyMilestones: e.target.checked })} className="mb-3" />

        <SettingsSaveBar>
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isAutoDirty || updateAuto.isPending}>
            {updateAuto.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isAutoDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
