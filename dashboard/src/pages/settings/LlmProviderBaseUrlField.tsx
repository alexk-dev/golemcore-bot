import type { ReactElement } from 'react';
import { Button, Form, InputGroup } from 'react-bootstrap';

import type { LlmProviderConfig } from '../../api/settingsTypes';
import { getSuggestedBaseUrl, normalizeApiType, toNullableString } from './llmProvidersSupport';

interface LlmProviderBaseUrlFieldProps {
  name: string;
  form: LlmProviderConfig;
  onFormChange: (form: LlmProviderConfig) => void;
}

export function LlmProviderBaseUrlField({ name, form, onFormChange }: LlmProviderBaseUrlFieldProps): ReactElement {
  const apiType = normalizeApiType(form.apiType);
  const hasBaseUrl = (form.baseUrl ?? '').trim().length > 0;
  const suggestedBaseUrl = getSuggestedBaseUrl(name, apiType);
  const shouldShowUseDefaultBaseUrl = suggestedBaseUrl != null && form.baseUrl !== suggestedBaseUrl;
  const shouldShowClearBaseUrl = (apiType === 'gemini' || apiType === 'gonka') && hasBaseUrl;

  return (
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
          <Button type="button" variant="secondary" onClick={() => onFormChange({ ...form, baseUrl: suggestedBaseUrl })}>
            Use default
          </Button>
        )}
        {shouldShowClearBaseUrl && (
          <Button type="button" variant="secondary" onClick={() => onFormChange({ ...form, baseUrl: null })}>
            Clear
          </Button>
        )}
      </InputGroup>
      <Form.Text className="text-body-secondary">
        {apiType === 'gemini'
          ? 'Gemini uses the native Google endpoint, so Base URL is usually left empty.'
          : apiType === 'gonka'
            ? 'Optional: direct /v1 endpoint for model discovery. Runtime requests use Gonka Source URL or endpoints below.'
            : suggestedBaseUrl != null
              ? `Recommended endpoint: ${suggestedBaseUrl}`
              : 'Leave empty to use the provider default endpoint.'}
      </Form.Text>
    </Form.Group>
  );
}
