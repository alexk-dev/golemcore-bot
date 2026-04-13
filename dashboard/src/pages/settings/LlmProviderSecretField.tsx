import type { ReactElement } from 'react';
import { Badge, Button, Form, InputGroup } from '../../components/ui/tailwind-components';

import type { LlmProviderConfig } from '../../api/settingsTypes';
import { toNullableString } from './llmProvidersSupport';

interface LlmProviderSecretFieldProps {
  name: string;
  form: LlmProviderConfig;
  isNew: boolean;
  showKey: boolean;
  onFormChange: (form: LlmProviderConfig) => void;
  onToggleShowKey: () => void;
}

export function LlmProviderSecretField({
  name,
  form,
  isNew,
  showKey,
  onFormChange,
  onToggleShowKey,
}: LlmProviderSecretFieldProps): ReactElement {
  return (
    <Form.Group className="mb-2">
      <Form.Label className="small fw-medium d-flex align-items-center gap-2">
        <span>API Key</span>
        {!isNew && form.apiKeyPresent === true && <Badge bg="success-subtle" text="success">Configured</Badge>}
        {(form.apiKey?.length ?? 0) > 0 && <Badge bg="info-subtle" text="info">Will update on save</Badge>}
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
  );
}
