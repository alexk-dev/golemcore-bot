import type { ReactElement } from 'react';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';
import type { LlmProviderConfig } from '../../api/settings';
import {
  API_TYPE_DETAILS,
  API_TYPE_OPTIONS,
  getDefaultApiTypeForProvider,
  normalizeApiType,
  toNullableInt,
} from './llmProvidersSupport';
import { LlmProviderBaseUrlField } from './LlmProviderBaseUrlField';
import { LlmProviderSecretField } from './LlmProviderSecretField';

export interface LlmProviderEditorCardProps {
  name: string;
  form: LlmProviderConfig;
  isNew: boolean;
  showKey: boolean;
  isSaving: boolean;
  onFormChange: (form: LlmProviderConfig) => void;
  onToggleShowKey: () => void;
  onSave: () => void;
  onCancel: () => void;
}

export function LlmProviderEditorCard({
  name,
  form,
  isNew,
  showKey,
  isSaving,
  onFormChange,
  onToggleShowKey,
  onSave,
  onCancel,
}: LlmProviderEditorCardProps): ReactElement {
  const apiType = normalizeApiType(form.apiType);
  const recommendedApiType = getDefaultApiTypeForProvider(name);

  return (
    <Card className="mb-3 border provider-editor-card">
      <Card.Body className="p-3">
        <h6 className="text-capitalize mb-3">{isNew ? `New provider: ${name}` : name}</h6>
        <Row className="g-2">
          <Col md={12}>
            <LlmProviderSecretField
              name={name}
              form={form}
              isNew={isNew}
              showKey={showKey}
              onFormChange={onFormChange}
              onToggleShowKey={onToggleShowKey}
            />
          </Col>
          <Col md={6}>
            <LlmProviderBaseUrlField name={name} form={form} onFormChange={onFormChange} />
          </Col>
          <Col md={3}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">API Type</Form.Label>
              <Form.Select
                size="sm"
                value={apiType}
                onChange={(event) => onFormChange({ ...form, apiType: normalizeApiType(event.target.value) })}
              >
                {API_TYPE_OPTIONS.map((type) => (
                  <option key={type} value={type}>{type}</option>
                ))}
              </Form.Select>
              <Form.Text className="text-body-secondary d-block">
                {API_TYPE_DETAILS[apiType].help}
              </Form.Text>
              <Form.Text
                className={`d-block ${recommendedApiType === apiType ? 'text-success' : 'text-warning'}`}
              >
                {recommendedApiType === apiType
                  ? `Matches recommended protocol for "${name}".`
                  : `Recommended for "${name}": ${recommendedApiType}.`}
              </Form.Text>
            </Form.Group>
          </Col>
          {apiType === 'openai' && (
            <Col md={3}>
              <Form.Group className="mb-2">
                <Form.Label className="small fw-medium">API Endpoint</Form.Label>
                <Form.Select
                  size="sm"
                  value={form.legacyApi === true ? 'legacy' : 'responses'}
                  onChange={(event) => onFormChange({ ...form, legacyApi: event.target.value === 'legacy' || null })}
                  title={form.legacyApi === true
                    ? 'Legacy: /v1/chat/completions — for proxies that do not support the Responses API'
                    : 'Default: /v1/responses — supports reasoning + tools on reasoning models'}
                >
                  <option value="responses">/v1/responses</option>
                  <option value="legacy">/v1/chat/completions</option>
                </Form.Select>
                <Form.Text className="text-body-secondary d-block">
                  {form.legacyApi === true
                    ? 'For proxies without Responses API support.'
                    : 'Supports reasoning + function tools.'}
                </Form.Text>
              </Form.Group>
            </Col>
          )}
          <Col md={3}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">Timeout (s)</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={3600}
                value={form.requestTimeoutSeconds ?? 300}
                onChange={(event) => onFormChange({
                  ...form,
                  requestTimeoutSeconds: toNullableInt(event.target.value) ?? 300,
                })}
              />
            </Form.Group>
          </Col>
        </Row>
        <div className="d-flex gap-2 mt-2">
          <Button type="button" variant="primary" size="sm" onClick={onSave} disabled={isSaving}>
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
