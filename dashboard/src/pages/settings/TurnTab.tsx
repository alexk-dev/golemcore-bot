import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateTurn } from '../../hooks/useSettings';
import type { TurnConfig } from '../../api/settings';
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
        <SettingsCardTitle title="Turn Budget" />
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
