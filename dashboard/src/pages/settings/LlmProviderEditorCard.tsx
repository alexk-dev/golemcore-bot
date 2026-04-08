import type { ReactElement } from 'react';
import { Badge, Button, Card, Col, Form, InputGroup, Row } from 'react-bootstrap';
import type { LlmProviderConfig } from '../../api/settings';
import {
  API_TYPE_DETAILS,
  API_TYPE_OPTIONS,
  getDefaultApiTypeForProvider,
  getSuggestedBaseUrl,
  normalizeApiType,
  toNullableInt,
  toNullableString,
} from './llmProvidersSupport';

export interface LlmProviderEditorCardProps {
  name: string;
  form: LlmProviderConfig;
  isNew: boolean;
  showKey: boolean;
  isSaving: boolean;
  isTesting: boolean;
  onFormChange: (form: LlmProviderConfig) => void;
  onToggleShowKey: () => void;
  onSave: () => void;
  onCancel: () => void;
  onTestDraft: () => void;
  onTestSaved: () => void;
}

export function LlmProviderEditorCard({
  name,
  form,
  isNew,
  showKey,
  isSaving,
  isTesting,
  onFormChange,
  onToggleShowKey,
  onSave,
  onCancel,
  onTestDraft,
  onTestSaved,
}: LlmProviderEditorCardProps): ReactElement {
  const apiType = normalizeApiType(form.apiType);
  const recommendedApiType = getDefaultApiTypeForProvider(name);
  const hasBaseUrl = (form.baseUrl ?? '').trim().length > 0;
  const suggestedBaseUrl = getSuggestedBaseUrl(name, apiType);
  const shouldShowUseDefaultBaseUrl = suggestedBaseUrl != null && form.baseUrl !== suggestedBaseUrl;
  const shouldShowClearBaseUrl = apiType === 'gemini' && hasBaseUrl;

  return (
    <Card className="mb-3 border provider-editor-card">
      <Card.Body className="p-3">
        <h6 className="text-capitalize mb-3">{isNew ? `New provider: ${name}` : name}</h6>
        <Row className="g-2">
          <Col md={12}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium d-flex align-items-center gap-2">
                <span>API Key</span>
                {!isNew && form.apiKeyPresent === true && (
                  <Badge bg="success-subtle" text="success">Configured</Badge>
                )}
                {(form.apiKey?.length ?? 0) > 0 && (
                  <Badge bg="info-subtle" text="info">Will update on save</Badge>
                )}
              </Form.Label>
              <InputGroup size="sm">
                <Form.Control
                  name={`llm-api-key-${name}`}
                  autoComplete="new-password"
                  autoCorrect="off"
                  autoCapitalize="off"
                  spellCheck={false}
                  data-lpignore="true"
                  placeholder={form.apiKeyPresent === true ? 'Secret is configured (hidden)' : 'Enter API key'}
                  type={showKey ? 'text' : 'password'}
                  value={form.apiKey ?? ''}
                  onChange={(event) => onFormChange({ ...form, apiKey: toNullableString(event.target.value) })}
                />
                <Button type="button" variant="secondary" aria-pressed={showKey} onClick={onToggleShowKey}>
                  {showKey ? 'Hide' : 'Show'}
                </Button>
              </InputGroup>
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">Base URL</Form.Label>
              <InputGroup size="sm">
                <Form.Control
                  type="url"
                  value={form.baseUrl ?? ''}
                  onChange={(event) => onFormChange({ ...form, baseUrl: toNullableString(event.target.value) })}
                  placeholder="https://api.example.com/v1"
                />
                {shouldShowUseDefaultBaseUrl && (
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={() => onFormChange({ ...form, baseUrl: suggestedBaseUrl })}
                  >
                    Use default
                  </Button>
                )}
                {shouldShowClearBaseUrl && (
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={() => onFormChange({ ...form, baseUrl: null })}
                  >
                    Clear
                  </Button>
                )}
              </InputGroup>
              <Form.Text className="text-body-secondary">
                {apiType === 'gemini'
                  ? 'Gemini uses the native Google endpoint, so Base URL is usually left empty.'
                  : suggestedBaseUrl != null
                    ? `Recommended endpoint: ${suggestedBaseUrl}`
                    : 'Leave empty to use the provider default endpoint.'}
              </Form.Text>
            </Form.Group>
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
          <Button type="button" variant="outline-primary" size="sm" onClick={onTestDraft} disabled={isSaving || isTesting}>
            {isTesting ? 'Testing...' : 'Test Draft'}
          </Button>
          {!isNew && (
            <Button type="button" variant="outline-secondary" size="sm" onClick={onTestSaved} disabled={isSaving || isTesting}>
              {isTesting ? 'Testing...' : 'Test Saved'}
            </Button>
          )}
          <Button type="button" variant="secondary" size="sm" onClick={onCancel} disabled={isSaving}>
            Cancel
          </Button>
        </div>
      </Card.Body>
    </Card>
  );
}
