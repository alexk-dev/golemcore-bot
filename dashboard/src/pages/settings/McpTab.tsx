import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateMcp } from '../../hooks/useSettings';
import type { McpConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

interface McpTabProps {
  config: McpConfig;
}

export default function McpTab({ config }: McpTabProps): ReactElement {
  const updateMcp = useUpdateMcp();
  const [form, setForm] = useState<McpConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateMcp.mutateAsync(form);
    toast.success('MCP settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="MCP (Model Context Protocol)" />
        <Form.Check
          type="switch"
          label="Enable MCP"
          checked={form.enabled ?? true}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />
        <Row className="g-3 mb-3">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">Default Startup Timeout (s)</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={300}
                value={form.defaultStartupTimeout ?? 30}
                onChange={(e) => setForm({ ...form, defaultStartupTimeout: toNullableInt(e.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">Default Idle Timeout (min)</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={120}
                value={form.defaultIdleTimeout ?? 5}
                onChange={(e) => setForm({ ...form, defaultIdleTimeout: toNullableInt(e.target.value) })}
              />
            </Form.Group>
          </Col>
        </Row>
        <SettingsSaveBar>
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateMcp.isPending}>
            {updateMcp.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
