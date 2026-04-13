import type { ReactElement } from 'react';
import { Button, Card, Col, Form, InputGroup, Row } from '../ui/tailwind-components';
import { FiEye, FiEyeOff } from 'react-icons/fi';
import type { MemoryPreset } from '../../api/settingsTypes';
import type { WebhookConfig } from '../../api/webhooks';

const DEFAULT_MAX_PAYLOAD_SIZE = 65536;
const DEFAULT_TIMEOUT_SECONDS = 300;

interface WebhookRuntimeCardProps {
  form: WebhookConfig;
  onChange: (next: WebhookConfig) => void;
  showToken: boolean;
  memoryPresets: MemoryPreset[];
  memoryPresetsLoading: boolean;
  onToggleShowToken: () => void;
}

function toNullableString(value: string): string | null {
  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

function toNumber(value: string, fallback: number): number {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return fallback;
  }
  return parsed;
}

function generateSecureToken(): string {
  const array = new Uint8Array(32);
  crypto.getRandomValues(array);
  return Array.from(array, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

export function WebhookRuntimeCard({
  form,
  onChange,
  showToken,
  memoryPresets,
  memoryPresetsLoading,
  onToggleShowToken,
}: WebhookRuntimeCardProps): ReactElement {
  const tokenToggleLabel = showToken ? 'Hide bearer token' : 'Show bearer token';
  const memoryPresetOptions: Array<Pick<MemoryPreset, 'id' | 'label'>> = memoryPresets.length > 0
    ? memoryPresets
    : [{ id: 'disabled', label: 'Memory Disabled' }];

  return (
    <Card className="settings-card h-100">
      <Card.Body>
        <Form.Check
          type="switch"
          label="Enable webhooks"
          checked={form.enabled}
          onChange={(event) => onChange({ ...form, enabled: event.target.checked })}
          className="mb-3"
        />

        <Row className="g-3 mb-2">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">Bearer Token</Form.Label>
              <InputGroup size="sm">
                <Form.Control
                  type={showToken ? 'text' : 'password'}
                  value={form.token ?? ''}
                  onChange={(event) => onChange({ ...form, token: toNullableString(event.target.value) })}
                  autoComplete="new-password"
                  autoCapitalize="off"
                  autoCorrect="off"
                  spellCheck={false}
                  placeholder="Paste token or generate"
                />
                <Button
                  type="button"
                  variant="secondary"
                  aria-label={tokenToggleLabel}
                  title={tokenToggleLabel}
                  aria-pressed={showToken}
                  onClick={onToggleShowToken}
                >
                  {showToken ? <FiEyeOff /> : <FiEye />}
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => onChange({ ...form, token: generateSecureToken() })}
                >
                  Generate
                </Button>
              </InputGroup>
            </Form.Group>
          </Col>

          <Col md={2}>
            <Form.Group>
              <Form.Label className="small fw-medium">Max Payload (bytes)</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                value={form.maxPayloadSize}
                onChange={(event) => onChange({
                  ...form,
                  maxPayloadSize: toNumber(event.target.value, DEFAULT_MAX_PAYLOAD_SIZE),
                })}
              />
            </Form.Group>
          </Col>

          <Col md={2}>
            <Form.Group>
              <Form.Label className="small fw-medium">Default Timeout (sec)</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                value={form.defaultTimeoutSeconds}
                onChange={(event) => onChange({
                  ...form,
                  defaultTimeoutSeconds: toNumber(event.target.value, DEFAULT_TIMEOUT_SECONDS),
                })}
              />
            </Form.Group>
          </Col>

          <Col md={2}>
            <Form.Group>
              <Form.Label className="small fw-medium">Memory Preset</Form.Label>
              <Form.Select
                size="sm"
                value={form.memoryPreset}
                disabled={memoryPresetsLoading}
                onChange={(event) => onChange({ ...form, memoryPreset: event.target.value })}
              >
                {memoryPresetOptions.map((preset) => (
                  <option key={preset.id} value={preset.id}>{preset.label}</option>
                ))}
              </Form.Select>
            </Form.Group>
          </Col>
        </Row>
      </Card.Body>
    </Card>
  );
}
