import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdatePlan } from '../../hooks/useSettings';
import type { PlanConfig } from '../../api/settingsTypes';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

export interface PlanModeTabProps {
  config: PlanConfig;
}

export default function PlanModeTab({ config }: PlanModeTabProps): ReactElement {
  const updatePlan = useUpdatePlan();
  const [form, setForm] = useState<PlanConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updatePlan.mutateAsync(form);
    toast.success('Plan mode settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle
          title="Plan Mode"
          tip="Review-before-execute workflow. The agent drafts a canonical plan and waits for approval before execution."
        />
        <Form.Check
          type="switch"
          label="Enable Plan Mode"
          checked={form.enabled ?? false}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />

        <Row className="g-3 mb-3">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max Active Plans <HelpTip text="Maximum number of concurrently active plans per session." />
              </Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={100}
                value={form.maxPlans ?? 5}
                onChange={(e) => setForm({ ...form, maxPlans: toNullableInt(e.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max Steps Per Plan <HelpTip text="Upper bound for collected tool steps in a single plan." />
              </Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={1000}
                value={form.maxStepsPerPlan ?? 50}
                onChange={(e) => setForm({ ...form, maxStepsPerPlan: toNullableInt(e.target.value) })}
              />
            </Form.Group>
          </Col>
        </Row>

        <Form.Check
          type="switch"
          label={<>Stop execution on first failed step <HelpTip text="When enabled, plan execution stops immediately after the first failed step. Otherwise, execution continues and the run completes with failures recorded." /></>}
          checked={form.stopOnFailure ?? true}
          onChange={(e) => setForm({ ...form, stopOnFailure: e.target.checked })}
          className="mb-3"
        />

        <SettingsSaveBar>
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updatePlan.isPending}>
            {updatePlan.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
