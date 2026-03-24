import { useEffect, useMemo, useState, type ReactElement } from 'react';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';

import type { TracingConfig } from '../../api/settings';
import { SettingsSaveBar, SaveStateHint } from '../../components/common/SettingsSaveBar';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateTracing } from '../../hooks/useSettings';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableInt(value: string): number | null {
  const parsed = Number.parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

function toInputValue(value: number | null | undefined): string {
  return value != null ? String(value) : '';
}

interface TracingTabProps {
  config: TracingConfig;
}

export default function TracingTab({ config }: TracingTabProps): ReactElement {
  const updateTracing = useUpdateTracing();
  const [form, setForm] = useState<TracingConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    // Keep the local form aligned with refreshed runtime-config payloads from the backend.
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateTracing.mutateAsync(form);
    toast.success('Tracing settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle
          title="Tracing"
          tip="Tracing stays enabled by default. Payload snapshots are optional and increase session size when turned on."
        />

        <Form.Check
          type="switch"
          label="Enable tracing"
          checked={form.enabled ?? true}
          onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
          className="mb-3"
        />

        <Form.Check
          type="switch"
          label="Payload snapshots"
          checked={form.payloadSnapshotsEnabled ?? false}
          onChange={(event) => setForm({ ...form, payloadSnapshotsEnabled: event.target.checked })}
          className="mb-3"
        />

        <Row className="g-3 mb-3">
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">Session trace budget (MB)</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                value={toInputValue(form.sessionTraceBudgetMb)}
                placeholder="128"
                onChange={(event) => setForm({ ...form, sessionTraceBudgetMb: toNullableInt(event.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">Max snapshot size (KB)</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                value={toInputValue(form.maxSnapshotSizeKb)}
                placeholder="256"
                onChange={(event) => setForm({ ...form, maxSnapshotSizeKb: toNullableInt(event.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">Max snapshots per span</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                value={toInputValue(form.maxSnapshotsPerSpan)}
                placeholder="10"
                onChange={(event) => setForm({ ...form, maxSnapshotsPerSpan: toNullableInt(event.target.value) })}
              />
            </Form.Group>
          </Col>
        </Row>

        <Row className="g-3 mb-3">
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">Max traces per session</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                value={toInputValue(form.maxTracesPerSession)}
                placeholder="100"
                onChange={(event) => setForm({ ...form, maxTracesPerSession: toNullableInt(event.target.value) })}
              />
            </Form.Group>
          </Col>
        </Row>

        <div className="small text-body-secondary mb-2">
          Payload capture toggles only apply when payload snapshots are enabled.
        </div>

        <Form.Check
          type="switch"
          label="Capture inbound payloads"
          checked={form.captureInboundPayloads ?? true}
          onChange={(event) => setForm({ ...form, captureInboundPayloads: event.target.checked })}
          className="mb-2"
        />
        <Form.Check
          type="switch"
          label="Capture outbound payloads"
          checked={form.captureOutboundPayloads ?? true}
          onChange={(event) => setForm({ ...form, captureOutboundPayloads: event.target.checked })}
          className="mb-2"
        />
        <Form.Check
          type="switch"
          label="Capture tool payloads"
          checked={form.captureToolPayloads ?? true}
          onChange={(event) => setForm({ ...form, captureToolPayloads: event.target.checked })}
          className="mb-2"
        />
        <Form.Check
          type="switch"
          label="Capture LLM payloads"
          checked={form.captureLlmPayloads ?? true}
          onChange={(event) => setForm({ ...form, captureLlmPayloads: event.target.checked })}
          className="mb-3"
        />

        <div className="small text-body-secondary mb-3">
          Sensitive request and response bodies may be retained in compressed form inside the session file when snapshots are enabled.
        </div>

        <SettingsSaveBar>
          <Button
            type="button"
            size="sm"
            variant="primary"
            onClick={() => { void handleSave(); }}
            disabled={!isDirty || updateTracing.isPending}
          >
            {updateTracing.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
