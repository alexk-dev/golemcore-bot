import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, Row } from '../../components/ui/tailwind-components';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateToolLoop, useUpdateTurn } from '../../hooks/useSettings';
import type { ToolLoopConfig, TurnConfig } from '../../api/settingsTypes';
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

interface RepeatGuardSettingsSectionProps {
  toolLoopForm: ToolLoopConfig;
  setToolLoopForm: (value: ToolLoopConfig) => void;
}

function RepeatGuardSettingsSection({
  toolLoopForm,
  setToolLoopForm,
}: RepeatGuardSettingsSectionProps): ReactElement {
  const updateToolLoopForm = (patch: Partial<ToolLoopConfig>): void => {
    setToolLoopForm({ ...toolLoopForm, ...patch });
  };

  return (
    <div className="border-top pt-3 mt-2">
      <SettingsCardTitle
        title="Tool Loop Repeat Guard"
        tip="Pre-execution guard that bounds repeated tool calls and autonomous ledger reuse."
      />
      <Row className="g-3 mb-3">
        <Col md={6}>
          <Form.Check
            type="switch"
            id="repeat-guard-enabled"
            label="Enable repeat guard"
            checked={toolLoopForm.repeatGuardEnabled ?? true}
            onChange={(e) => updateToolLoopForm({ repeatGuardEnabled: e.target.checked })}
          />
        </Col>
        <Col md={6}>
          <Form.Check
            type="switch"
            id="repeat-guard-shadow-mode"
            label="Shadow mode"
            checked={toolLoopForm.repeatGuardShadowMode ?? false}
            onChange={(e) => updateToolLoopForm({ repeatGuardShadowMode: e.target.checked })}
          />
        </Col>
      </Row>
      <Row className="g-3 mb-3">
        <Col md={4}>
          <Form.Group>
            <Form.Label className="small fw-medium">
              Same Observe Calls <HelpTip text="Successful identical observation calls allowed in the current verified state before blocking." />
            </Form.Label>
            <Form.Control
              size="sm"
              type="number"
              min={1}
              value={toolLoopForm.repeatGuardMaxSameObservePerTurn ?? 2}
              onChange={(e) => updateToolLoopForm({
                repeatGuardMaxSameObservePerTurn: toNullableInt(e.target.value),
              })}
            />
          </Form.Group>
        </Col>
        <Col md={4}>
          <Form.Group>
            <Form.Label className="small fw-medium">
              Same Unknown Calls <HelpTip text="Successful identical unknown executions allowed before blocking in the current verified state." />
            </Form.Label>
            <Form.Control
              size="sm"
              type="number"
              min={1}
              value={toolLoopForm.repeatGuardMaxSameUnknownPerTurn ?? 2}
              onChange={(e) => updateToolLoopForm({
                repeatGuardMaxSameUnknownPerTurn: toNullableInt(e.target.value),
              })}
            />
          </Form.Group>
        </Col>
        <Col md={4}>
          <Form.Group>
            <Form.Label className="small fw-medium">
              Blocked Repeats <HelpTip text="Repeated ignored guard hints allowed before the turn stops." />
            </Form.Label>
            <Form.Control
              size="sm"
              type="number"
              min={1}
              value={toolLoopForm.repeatGuardMaxBlockedRepeatsPerTurn ?? 4}
              onChange={(e) => updateToolLoopForm({
                repeatGuardMaxBlockedRepeatsPerTurn: toNullableInt(e.target.value),
              })}
            />
          </Form.Group>
        </Col>
      </Row>
      <Row className="g-3 mb-3">
        <Col md={4}>
          <Form.Group>
            <Form.Label className="small fw-medium">
              Poll Backoff <HelpTip text="Minimum seconds before repeating the same polling call." />
            </Form.Label>
            <Form.Control
              size="sm"
              type="number"
              min={1}
              value={toolLoopForm.repeatGuardMinPollIntervalSeconds ?? 60}
              onChange={(e) => updateToolLoopForm({
                repeatGuardMinPollIntervalSeconds: toNullableInt(e.target.value),
              })}
            />
          </Form.Group>
        </Col>
        <Col md={4}>
          <Form.Group>
            <Form.Label className="small fw-medium">
              Auto Ledger TTL <HelpTip text="Minutes to keep autonomous task/goal repeat ledger records for observations, polling, unknown executions and guard synthetic records." />
            </Form.Label>
            <Form.Control
              size="sm"
              type="number"
              min={1}
              value={toolLoopForm.repeatGuardAutoLedgerTtlMinutes ?? 120}
              onChange={(e) => updateToolLoopForm({
                repeatGuardAutoLedgerTtlMinutes: toNullableInt(e.target.value),
              })}
            />
          </Form.Group>
        </Col>
      </Row>
    </div>
  );
}

interface TurnTabProps {
  config: TurnConfig;
  toolLoopConfig: ToolLoopConfig;
}

export default function TurnTab({ config, toolLoopConfig }: TurnTabProps): ReactElement {
  const updateTurn = useUpdateTurn();
  const updateToolLoop = useUpdateToolLoop();
  const [form, setForm] = useState<TurnConfig>({ ...config });
  const [toolLoopForm, setToolLoopForm] = useState<ToolLoopConfig>({ ...toolLoopConfig });
  const turnDirty = useMemo(() => hasDiff(form, config), [form, config]);
  const toolLoopDirty = useMemo(() => hasDiff(toolLoopForm, toolLoopConfig), [toolLoopForm, toolLoopConfig]);
  const isDirty = turnDirty || toolLoopDirty;
  const isSaving = updateTurn.isPending || updateToolLoop.isPending;
  const progressUpdatesEnabled = form.progressUpdatesEnabled ?? true;

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  useEffect(() => {
    setToolLoopForm({ ...toolLoopConfig });
  }, [toolLoopConfig]);

  const handleSave = async (): Promise<void> => {
    if (toolLoopDirty) {
      await updateToolLoop.mutateAsync(toolLoopForm);
    }
    if (turnDirty) {
      await updateTurn.mutateAsync(form);
    }
    toast.success('Turn budget settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="Tool Loop Limits" />
        <Row className="g-3 mb-3">
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max Internal AI Calls <HelpTip text="Maximum LLM requests inside the internal tool loop. Saves to toolLoop.maxLlmCalls." />
              </Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                value={toolLoopForm.maxLlmCalls ?? 20}
                onChange={(e) =>
                  setToolLoopForm({
                    ...toolLoopForm,
                    maxLlmCalls: toNullableInt(e.target.value),
                  })
                }
              />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max Tool Executions <HelpTip text="Maximum tool executions inside the internal tool loop. Saves to toolLoop.maxToolExecutions." />
              </Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                value={toolLoopForm.maxToolExecutions ?? 80}
                onChange={(e) =>
                  setToolLoopForm({
                    ...toolLoopForm,
                    maxToolExecutions: toNullableInt(e.target.value),
                  })
                }
              />
            </Form.Group>
          </Col>
        </Row>
        <RepeatGuardSettingsSection toolLoopForm={toolLoopForm} setToolLoopForm={setToolLoopForm} />
        <div className="border-top pt-3 mt-2">
          <SettingsCardTitle title="Turn Settings" />
          <Row className="g-3 mb-3">
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
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || isSaving}>
            {isSaving ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
