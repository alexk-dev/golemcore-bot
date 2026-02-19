import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, InputGroup, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import { SecretStatusBadges } from '../../components/common/SecretStatusBadges';
import { getSecretInputType, getSecretPlaceholder, getSecretToggleLabel } from '../../components/common/secretInputUtils';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateRag } from '../../hooks/useSettings';
import type { RagConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

function getSaveButtonLabel(isPending: boolean): string {
  return isPending ? 'Saving...' : 'Save';
}

interface RagTabProps {
  config: RagConfig;
}

export default function RagTab({ config }: RagTabProps): ReactElement {
  const updateRag = useUpdateRag();
  const [form, setForm] = useState<RagConfig>({ ...config });
  const [showApiKey, setShowApiKey] = useState(false);
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);
  const hasStoredApiKey = config.apiKeyPresent === true;
  const willUpdateApiKey = (form.apiKey?.length ?? 0) > 0;
  const apiKeyInputType = getSecretInputType(showApiKey);
  const apiKeyToggleLabel = getSecretToggleLabel(showApiKey);
  const saveLabel = getSaveButtonLabel(updateRag.isPending);

  // Reset local draft when backend config changes (e.g. after successful save/refetch).
  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateRag.mutateAsync(form);
    toast.success('RAG settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="RAG (LightRAG)" />

        <Form.Check
          type="switch"
          label="Enable RAG"
          checked={form.enabled ?? false}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />

        <Row className="g-3">
          <Col md={8}>
            <Form.Group>
              <Form.Label className="small fw-medium">RAG URL</Form.Label>
              <Form.Control
                size="sm"
                value={form.url ?? ''}
                onChange={(e) => setForm({ ...form, url: toNullableString(e.target.value) })}
                placeholder="http://localhost:9621"
              />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">Query Mode</Form.Label>
              <Form.Select size="sm" value={form.queryMode ?? 'hybrid'} onChange={(e) => setForm({ ...form, queryMode: e.target.value })}>
                <option value="hybrid">hybrid</option>
                <option value="local">local</option>
                <option value="global">global</option>
                <option value="naive">naive</option>
              </Form.Select>
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">Timeout Seconds</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={120}
                value={form.timeoutSeconds ?? 10}
                onChange={(e) => setForm({ ...form, timeoutSeconds: Number(e.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">Index Min Length</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={2000}
                value={form.indexMinLength ?? 50}
                onChange={(e) => setForm({ ...form, indexMinLength: Number(e.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={12}>
            <Form.Group>
              <Form.Label className="small fw-medium d-flex align-items-center gap-2">
                API Key (optional)
                <SecretStatusBadges hasStoredSecret={hasStoredApiKey} willUpdateSecret={willUpdateApiKey} />
              </Form.Label>
              <InputGroup size="sm">
                <Form.Control
                  type={apiKeyInputType}
                  value={form.apiKey ?? ''}
                  onChange={(e) => setForm({ ...form, apiKey: toNullableString(e.target.value) })}
                  placeholder={getSecretPlaceholder(hasStoredApiKey, 'Enter API key')}
                  autoComplete="new-password"
                  autoCapitalize="off"
                  autoCorrect="off"
                  spellCheck={false}
                />
                <Button type="button" variant="secondary" onClick={() => setShowApiKey(!showApiKey)}>
                  {apiKeyToggleLabel}
                </Button>
              </InputGroup>
            </Form.Group>
          </Col>
        </Row>

        <SettingsSaveBar className="mt-3">
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateRag.isPending}>
            {saveLabel}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
