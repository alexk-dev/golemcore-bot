import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateTurn } from '../../hooks/useSettings';
import type { TurnConfig } from '../../api/settingsTypes';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

interface TurnTabProps {
  config: TurnConfig;
}

export default function TurnTab({ config }: TurnTabProps): ReactElement {
  const updateTurn = useUpdateTurn();
  const [form, setForm] = useState<TurnConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);
  const progressUpdatesEnabled = form.progressUpdatesEnabled ?? true;

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateTurn.mutateAsync(form);
    toast.success('Turn budget settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="Turn Settings" />
        <Row className="g-3 mb-3">
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max LLM Calls <HelpTip text="Maximum LLM requests within a single turn." />
              </Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                value={form.maxLlmCalls ?? 200}
                onChange={(e) => setForm({ ...form, maxLlmCalls: toNullableInt(e.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max Tool Executions <HelpTip text="Maximum tool executions within a single turn." />
              </Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                value={form.maxToolExecutions ?? 500}
                onChange={(e) => setForm({ ...form, maxToolExecutions: toNullableInt(e.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Deadline <HelpTip text="Turn deadline in ISO-8601 duration format, e.g. PT1H or PT30M." />
              </Form.Label>
              <Form.Control
                size="sm"
                value={form.deadline ?? 'PT1H'}
                onChange={(e) => setForm({ ...form, deadline: toNullableString(e.target.value) })}
                placeholder="PT1H"
              />
            </Form.Group>
          </Col>
        </Row>
        <div className="border-top pt-3 mt-2">
          <SettingsCardTitle title="Live Progress" />
          <p className="small text-body-secondary mb-3">
            Share a short progress update while the agent is working, instead of posting every tool call.
          </p>
          <Row className="g-3 mb-3">
            <Col md={6}>
              <Form.Check
                type="switch"
                id="turn-progress-updates-enabled"
                label="Show live progress updates"
                checked={progressUpdatesEnabled}
                onChange={(e) => setForm({ ...form, progressUpdatesEnabled: e.target.checked })}
              />
            </Col>
            <Col md={6}>
              <Form.Check
                type="switch"
                id="turn-progress-intent-enabled"
                label="Start by explaining the next steps"
                checked={form.progressIntentEnabled ?? true}
                disabled={!progressUpdatesEnabled}
                onChange={(e) => setForm({ ...form, progressIntentEnabled: e.target.checked })}
              />
            </Col>
          </Row>
          <Row className="g-3">
            <Col md={4}>
              <Form.Group>
                <Form.Label className="small fw-medium">
                  Batch Size <HelpTip text="How many tool runs to group before posting a progress update." />
                </Form.Label>
                <Form.Control
                  size="sm"
                  type="number"
                  min={1}
                  max={50}
                  disabled={!progressUpdatesEnabled}
                  value={form.progressBatchSize ?? 8}
                  onChange={(e) => setForm({ ...form, progressBatchSize: toNullableInt(e.target.value) })}
                />
              </Form.Group>
            </Col>
            <Col md={4}>
              <Form.Group>
                <Form.Label className="small fw-medium">
                  Max Wait (seconds) <HelpTip text="Post a progress update sooner if a batch stays open for too long." />
                </Form.Label>
                <Form.Control
                  size="sm"
                  type="number"
                  min={1}
                  max={300}
                  disabled={!progressUpdatesEnabled}
                  value={form.progressMaxSilenceSeconds ?? 10}
                  onChange={(e) => setForm({ ...form, progressMaxSilenceSeconds: toNullableInt(e.target.value) })}
                />
              </Form.Group>
            </Col>
            <Col md={4}>
              <Form.Group>
                <Form.Label className="small fw-medium">
                  Summary Timeout (ms) <HelpTip text="How long to wait for the progress summary before falling back to a simple update." />
                </Form.Label>
                <Form.Control
                  size="sm"
                  type="number"
                  min={1000}
                  max={60000}
                  disabled={!progressUpdatesEnabled}
                  value={form.progressSummaryTimeoutMs ?? 8000}
                  onChange={(e) => setForm({ ...form, progressSummaryTimeoutMs: toNullableInt(e.target.value) })}
                />
              </Form.Group>
            </Col>
          </Row>
        </div>
        <SettingsSaveBar>
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateTurn.isPending}>
            {updateTurn.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
