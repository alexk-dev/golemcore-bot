import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, OverlayTrigger, Row, Tooltip } from 'react-bootstrap';
import { FiHelpCircle } from 'react-icons/fi';
import toast from 'react-hot-toast';
import { useUpdateTurn } from '../../hooks/useSettings';
import type { TurnConfig } from '../../api/settings';

function Tip({ text }: { text: string }): ReactElement {
  return (
    <OverlayTrigger placement="top" overlay={<Tooltip>{text}</Tooltip>}>
      <span className="setting-tip"><FiHelpCircle /></span>
    </OverlayTrigger>
  );
}

function SaveStateHint({ isDirty }: { isDirty: boolean }): ReactElement {
  return <small className="text-body-secondary">{isDirty ? 'Unsaved changes' : 'All changes saved'}</small>;
}

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
        <Card.Title className="h6 mb-3">Turn Budget</Card.Title>
        <Row className="g-3 mb-3">
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max LLM Calls <Tip text="Maximum LLM requests within a single turn." />
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
                Max Tool Executions <Tip text="Maximum tool executions within a single turn." />
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
                Deadline <Tip text="Turn deadline in ISO-8601 duration format, e.g. PT1H or PT30M." />
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
        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateTurn.isPending}>
            {updateTurn.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}
