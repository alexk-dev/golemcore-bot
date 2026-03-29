import { type ReactElement, useState } from 'react';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';
import { FiPlus, FiTrash2 } from 'react-icons/fi';
import type { McpCatalogEntry } from '../../api/settings';

interface EnvEntry {
  key: string;
  value: string;
}

function toEnvEntries(env: Record<string, string>): EnvEntry[] {
  return Object.entries(env).map(([key, value]) => ({ key, value }));
}

function fromEnvEntries(entries: EnvEntry[]): Record<string, string> {
  const result: Record<string, string> = {};
  entries.forEach((entry) => {
    const trimmedKey = entry.key.trim();
    if (trimmedKey.length > 0) {
      result[trimmedKey] = entry.value;
    }
  });
  return result;
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

export interface McpCatalogEntryEditorProps {
  name: string;
  form: McpCatalogEntry;
  isNew: boolean;
  isSaving: boolean;
  onFormChange: (form: McpCatalogEntry) => void;
  onSave: () => void;
  onCancel: () => void;
}

export function McpCatalogEntryEditor({
  name, form, isNew, isSaving, onFormChange, onSave, onCancel,
}: McpCatalogEntryEditorProps): ReactElement {
  const [envEntries, setEnvEntries] = useState<EnvEntry[]>(() => toEnvEntries(form.env));

  const updateEnv = (entries: EnvEntry[]): void => {
    setEnvEntries(entries);
    onFormChange({ ...form, env: fromEnvEntries(entries) });
  };

  return (
    <Card className="mb-3 border">
      <Card.Body className="p-3">
        <h6 className="mb-3">{isNew ? `New server: ${name}` : `Edit: ${name}`}</h6>
        <Row className="g-2">
          <Col md={12}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">Description</Form.Label>
              <Form.Control
                size="sm"
                type="text"
                placeholder="Human-readable description"
                value={form.description ?? ''}
                onChange={(e) => onFormChange({ ...form, description: e.target.value || null })}
              />
            </Form.Group>
          </Col>
          <Col md={12}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">Command *</Form.Label>
              <Form.Control
                size="sm"
                type="text"
                placeholder="npx -y @modelcontextprotocol/server-github"
                value={form.command}
                onChange={(e) => onFormChange({ ...form, command: e.target.value })}
              />
              <Form.Text className="text-body-secondary">
                Shell command to start the MCP server (stdio transport).
              </Form.Text>
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">Startup Timeout (s)</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={300}
                value={form.startupTimeoutSeconds ?? 30}
                onChange={(e) => onFormChange({ ...form, startupTimeoutSeconds: toNullableInt(e.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">Idle Timeout (min)</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={120}
                value={form.idleTimeoutMinutes ?? 5}
                onChange={(e) => onFormChange({ ...form, idleTimeoutMinutes: toNullableInt(e.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={12}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">Environment Variables</Form.Label>
              {envEntries.map((entry, idx) => (
                <Row key={idx} className="g-1 mb-1 align-items-center">
                  <Col xs={5}>
                    <Form.Control
                      size="sm"
                      placeholder="KEY"
                      value={entry.key}
                      onChange={(e) => {
                        const updated = [...envEntries];
                        updated[idx] = { ...entry, key: e.target.value };
                        updateEnv(updated);
                      }}
                    />
                  </Col>
                  <Col xs={5}>
                    <Form.Control
                      size="sm"
                      placeholder="value or ${VAR}"
                      value={entry.value}
                      onChange={(e) => {
                        const updated = [...envEntries];
                        updated[idx] = { ...entry, value: e.target.value };
                        updateEnv(updated);
                      }}
                    />
                  </Col>
                  <Col xs={2}>
                    <Button
                      type="button"
                      variant="danger"
                      size="sm"
                      onClick={() => updateEnv(envEntries.filter((_, i) => i !== idx))}
                    >
                      <FiTrash2 size={14} />
                    </Button>
                  </Col>
                </Row>
              ))}
              <Button
                type="button"
                variant="secondary"
                size="sm"
                className="mt-1"
                onClick={() => updateEnv([...envEntries, { key: '', value: '' }])}
              >
                <FiPlus size={14} className="me-1" />
                Add Variable
              </Button>
              <Form.Text className="text-body-secondary d-block mt-1">
                Use {'${VAR_NAME}'} to reference OS environment variables or skill variables.
              </Form.Text>
            </Form.Group>
          </Col>
        </Row>
        <div className="d-flex gap-2 mt-2">
          <Button type="button" variant="primary" size="sm" onClick={onSave} disabled={isSaving || form.command.trim().length === 0}>
            {isSaving ? 'Saving...' : 'Save'}
          </Button>
          <Button type="button" variant="secondary" size="sm" onClick={onCancel} disabled={isSaving}>
            Cancel
          </Button>
        </div>
      </Card.Body>
    </Card>
  );
}
