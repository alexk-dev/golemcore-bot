import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Badge, Button, Card, Col, Form, InputGroup, Row, Table } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import ConfirmModal from '../../components/common/ConfirmModal';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useSettings, useUpdateWebhooks } from '../../hooks/useSettings';
import type { HookMapping, WebhookConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

const DEFAULT_WEBHOOK_CONFIG: WebhookConfig = {
  enabled: false,
  token: null,
  maxPayloadSize: 65536,
  defaultTimeoutSeconds: 300,
  mappings: [],
};

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

function generateSecureToken(): string {
  const array = new Uint8Array(32);
  crypto.getRandomValues(array);
  return Array.from(array, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

interface WebhookEditorProps {
  form: WebhookConfig;
  editIdx: number;
  updateMapping: (idx: number, partial: Partial<HookMapping>) => void;
}

function WebhookEditor({ form, editIdx, updateMapping }: WebhookEditorProps): ReactElement {
  return (
    <Card className="mb-3 webhook-editor-card border">
      <Card.Body className="p-3">
        <Row className="g-2">
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Name <HelpTip text="URL path segment: /api/hooks/{name}" />
              </Form.Label>
              <Form.Control size="sm" value={form.mappings[editIdx].name}
                onChange={(e) => updateMapping(editIdx, { name: e.target.value })} />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Action <HelpTip text="'wake' sends a fire-and-forget message; 'agent' runs a full agent turn and waits for response" />
              </Form.Label>
              <Form.Select size="sm" value={form.mappings[editIdx].action}
                onChange={(e) => updateMapping(editIdx, { action: e.target.value })}>
                <option value="wake">Wake</option>
                <option value="agent">Agent</option>
              </Form.Select>
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Auth Mode <HelpTip text="'bearer' uses Authorization header; 'hmac' uses HMAC-SHA256 signature verification" />
              </Form.Label>
              <Form.Select size="sm" value={form.mappings[editIdx].authMode}
                onChange={(e) => updateMapping(editIdx, { authMode: e.target.value })}>
                <option value="bearer">Bearer</option>
                <option value="hmac">HMAC</option>
              </Form.Select>
            </Form.Group>
          </Col>
          <Col md={12}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Message Template <HelpTip text="Template for the message sent to the bot. Use {field.path} placeholders to extract values from webhook JSON payload." />
              </Form.Label>
              <Form.Control size="sm" as="textarea" rows={2}
                value={form.mappings[editIdx].messageTemplate ?? ''}
                onChange={(e) => updateMapping(editIdx, { messageTemplate: toNullableString(e.target.value) })}
                placeholder="e.g. New {action.type} event: {repository.full_name}" />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Check type="switch" label={<>Deliver to channel <HelpTip text="Forward the bot response to a messaging channel (e.g. Telegram)" /></>}
              className="mt-2"
              checked={form.mappings[editIdx].deliver}
              onChange={(e) => updateMapping(editIdx, { deliver: e.target.checked })} />
          </Col>
          {form.mappings[editIdx].deliver && (
            <>
              <Col md={4}>
                <Form.Group>
                  <Form.Label className="small fw-medium">Channel</Form.Label>
                  <Form.Control size="sm" value={form.mappings[editIdx].channel ?? ''}
                    onChange={(e) => updateMapping(editIdx, { channel: toNullableString(e.target.value) })} placeholder="telegram" />
                </Form.Group>
              </Col>
              <Col md={4}>
                <Form.Group>
                  <Form.Label className="small fw-medium">To (Chat ID)</Form.Label>
                  <Form.Control size="sm" value={form.mappings[editIdx].to ?? ''}
                    onChange={(e) => updateMapping(editIdx, { to: toNullableString(e.target.value) })} />
                </Form.Group>
              </Col>
            </>
          )}
        </Row>
      </Card.Body>
    </Card>
  );
}

export default function WebhooksTab(): ReactElement {
  const { data: settings } = useSettings();
  const updateWebhooks = useUpdateWebhooks();

  const webhookConfig = useMemo<WebhookConfig>(() => settings?.webhooks ?? DEFAULT_WEBHOOK_CONFIG, [settings?.webhooks]);

  const [form, setForm] = useState<WebhookConfig>(webhookConfig);
  const [editIdx, setEditIdx] = useState<number | null>(null);
  const [deleteMappingIdx, setDeleteMappingIdx] = useState<number | null>(null);
  const isWebhooksDirty = useMemo(() => hasDiff(form, webhookConfig), [form, webhookConfig]);

  useEffect(() => {
    if (settings?.webhooks != null) {
      setForm(settings.webhooks);
    }
  }, [settings]);

  const handleSave = async (): Promise<void> => {
    await updateWebhooks.mutateAsync(form);
    toast.success('Webhook settings saved');
  };

  const handleGenerateToken = (): void => {
    const token = generateSecureToken();
    setForm({ ...form, token });
    toast.success('Token generated');
  };

  const addMapping = (): void => {
    const newMapping: HookMapping = {
      name: '', action: 'wake', authMode: 'bearer',
      hmacHeader: null, hmacSecret: null, hmacPrefix: null,
      messageTemplate: null, model: null,
      deliver: false, channel: null, to: null,
    };
    setForm({ ...form, mappings: [...form.mappings, newMapping] });
    setEditIdx(form.mappings.length);
  };

  const removeMapping = (idx: number): void => {
    const mappings = form.mappings.filter((_, index) => index !== idx);
    setForm({ ...form, mappings });
    if (editIdx === idx) {
      setEditIdx(null);
    }
  };

  const updateMapping = (idx: number, partial: Partial<HookMapping>): void => {
    const mappings = form.mappings.map((mapping, index) => (index === idx ? { ...mapping, ...partial } : mapping));
    setForm({ ...form, mappings });
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle
          title="Webhooks"
          tip="Inbound HTTP webhooks allow external services to trigger bot actions"
        />
        <Form.Check type="switch" label="Enable Webhooks" checked={form.enabled}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })} className="mb-3" />

        <Row className="g-3 mb-3">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Bearer Token <HelpTip text="Secret token for authenticating incoming webhook requests" />
              </Form.Label>
              <InputGroup size="sm">
                <Form.Control type="password" value={form.token ?? ''}
                  onChange={(e) => setForm({ ...form, token: toNullableString(e.target.value) })} />
                <Button type="button" variant="secondary" onClick={handleGenerateToken} title="Generate random token">
                  Generate
                </Button>
              </InputGroup>
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max Payload Size <HelpTip text="Maximum allowed webhook request body size in bytes" />
              </Form.Label>
              <Form.Control size="sm" type="number" value={form.maxPayloadSize}
                onChange={(e) => setForm({ ...form, maxPayloadSize: toNullableInt(e.target.value) ?? 65536 })} />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Default Timeout (s) <HelpTip text="Maximum time in seconds for the bot to process an agent webhook request" />
              </Form.Label>
              <Form.Control size="sm" type="number" value={form.defaultTimeoutSeconds}
                onChange={(e) => setForm({ ...form, defaultTimeoutSeconds: toNullableInt(e.target.value) ?? 300 })} />
            </Form.Group>
          </Col>
        </Row>

        <div className="d-flex align-items-center justify-content-between mb-2">
          <span className="small fw-medium">Hook Mappings <HelpTip text="Named endpoints (/api/hooks/{name}) that map incoming webhooks to bot actions" /></span>
          <Button type="button" variant="primary" size="sm" onClick={addMapping}>Add Webhook</Button>
        </div>

        {form.mappings.length > 0 ? (
          <Table size="sm" hover responsive className="mb-3 dashboard-table responsive-table webhooks-table">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Action</th>
                <th scope="col">Auth</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {form.mappings.map((mapping, idx) => (
                <tr key={idx}>
                  <td data-label="Name">{mapping.name.length > 0 ? mapping.name : <em className="text-body-secondary">unnamed</em>}</td>
                  <td data-label="Action"><Badge bg={mapping.action === 'agent' ? 'primary' : 'secondary'}>{mapping.action}</Badge></td>
                  <td data-label="Auth" className="small">{mapping.authMode}</td>
                  <td data-label="Actions" className="text-end">
                    <div className="d-flex flex-wrap gap-1 webhook-actions">
                      <Button type="button"
                        size="sm"
                        variant="secondary"
                        className="webhook-action-btn"
                        onClick={() => setEditIdx(editIdx === idx ? null : idx)}
                      >
                        {editIdx === idx ? 'Close' : 'Edit'}
                      </Button>
                      <Button type="button"
                        size="sm"
                        variant="danger"
                        className="webhook-action-btn"
                        onClick={() => setDeleteMappingIdx(idx)}
                      >
                        Delete
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        ) : (
          <p className="text-body-secondary small">No webhook mappings configured</p>
        )}

        {editIdx !== null && form.mappings[editIdx] != null && (
          <WebhookEditor form={form} editIdx={editIdx} updateMapping={updateMapping} />
        )}

        <SettingsSaveBar>
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isWebhooksDirty || updateWebhooks.isPending}>
            {updateWebhooks.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isWebhooksDirty} />
        </SettingsSaveBar>
      </Card.Body>

      <ConfirmModal
        show={deleteMappingIdx !== null}
        title="Delete Webhook Mapping"
        message="This mapping will be removed permanently from runtime settings. This action cannot be undone."
        confirmLabel="Delete"
        confirmVariant="danger"
        onConfirm={() => {
          if (deleteMappingIdx !== null) {
            removeMapping(deleteMappingIdx);
            setDeleteMappingIdx(null);
          }
        }}
        onCancel={() => setDeleteMappingIdx(null)}
      />
    </Card>
  );
}
