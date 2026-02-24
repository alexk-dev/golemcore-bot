import { type ChangeEvent, type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, InputGroup, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import type { PluginSettingsFieldSchema, PluginSettingsSectionSchema } from '../../api/plugins';
import type { RuntimeConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../common/SettingsSaveBar';
import SettingsCardTitle from '../common/SettingsCardTitle';
import {
  useUpdateAdvanced,
  useUpdateRag,
  useUpdateTelegram,
  useUpdateTools,
  useUpdateVoice,
} from '../../hooks/useSettings';
import { extractErrorMessage } from '../../utils/extractErrorMessage';

interface PluginSettingsPanelProps {
  schema: PluginSettingsSectionSchema;
  runtimeConfig: RuntimeConfig;
}

interface FieldControlProps {
  field: PluginSettingsFieldSchema;
  value: PluginFormValue | undefined;
  passwordVisible: boolean;
  onTogglePassword: (fieldKey: string) => void;
  onChange: (fieldKey: string, value: PluginFormValue) => void;
}

type PluginFormValue = string | number | boolean | null;
type PluginFormValues = Record<string, PluginFormValue>;
type JsonRecord = Record<string, unknown>;
type KnownPluginSectionKey =
  | 'telegram'
  | 'tool-browser'
  | 'tool-brave'
  | 'tool-email'
  | 'tool-voice'
  | 'voice-elevenlabs'
  | 'voice-whisper'
  | 'rag'
  | 'advanced-security';

function isKnownPluginSectionKey(sectionKey: string): sectionKey is KnownPluginSectionKey {
  return [
    'telegram',
    'tool-browser',
    'tool-brave',
    'tool-email',
    'tool-voice',
    'voice-elevenlabs',
    'voice-whisper',
    'rag',
    'advanced-security',
  ].includes(sectionKey);
}

function toInputValue(value: PluginFormValue | undefined): string | number {
  if (typeof value === 'number') {
    return value;
  }
  if (typeof value === 'string') {
    return value;
  }
  return '';
}

function readPathValue(source: unknown, path: string): PluginFormValue {
  const segments = path.split('.');
  let current: unknown = source;

  for (const segment of segments) {
    if (current == null || typeof current !== 'object' || Array.isArray(current)) {
      return null;
    }
    current = (current as JsonRecord)[segment];
  }

  if (typeof current === 'string' || typeof current === 'number' || typeof current === 'boolean') {
    return current;
  }
  return null;
}

function normalizeFieldValueForForm(field: PluginSettingsFieldSchema, rawValue: PluginFormValue): PluginFormValue {
  if (field.type === 'switch') {
    return rawValue === true;
  }
  if (field.type === 'number') {
    return typeof rawValue === 'number' && Number.isFinite(rawValue) ? rawValue : null;
  }
  return typeof rawValue === 'string' ? rawValue : null;
}

function buildInitialValues(schema: PluginSettingsSectionSchema, runtimeConfig: RuntimeConfig): PluginFormValues {
  const values: PluginFormValues = {};
  schema.fields.forEach((field) => {
    const rawValue = readPathValue(runtimeConfig, field.key);
    values[field.key] = normalizeFieldValueForForm(field, rawValue);
  });
  return values;
}

function normalizeValueForSave(field: PluginSettingsFieldSchema, value: PluginFormValue | undefined): PluginFormValue {
  if (field.type === 'switch') {
    return value === true;
  }
  if (field.type === 'number') {
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
  }
  if (typeof value !== 'string') {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function setPathValue(target: unknown, path: string, value: PluginFormValue): boolean {
  if (target == null || typeof target !== 'object' || Array.isArray(target)) {
    return false;
  }

  const segments = path.split('.');
  if (segments.length === 0) {
    return false;
  }

  let current: JsonRecord = target as JsonRecord;
  for (let index = 0; index < segments.length - 1; index += 1) {
    const segment = segments[index];
    const next = current[segment];
    if (next == null || typeof next !== 'object' || Array.isArray(next)) {
      return false;
    }
    current = next as JsonRecord;
  }

  const tail = segments[segments.length - 1];
  current[tail] = value;
  return true;
}

function cloneRuntimeConfig(runtimeConfig: RuntimeConfig): RuntimeConfig {
  return JSON.parse(JSON.stringify(runtimeConfig)) as RuntimeConfig;
}

function applyFormValuesToRuntime(
  schema: PluginSettingsSectionSchema,
  runtimeConfig: RuntimeConfig,
  formValues: PluginFormValues,
): RuntimeConfig {
  const nextRuntime = cloneRuntimeConfig(runtimeConfig);
  schema.fields.forEach((field) => {
    const normalized = normalizeValueForSave(field, formValues[field.key]);
    setPathValue(nextRuntime as unknown, field.key, normalized);
  });
  return nextRuntime;
}

function renderSwitchField(
  field: PluginSettingsFieldSchema,
  value: PluginFormValue | undefined,
  onChange: (fieldKey: string, nextValue: PluginFormValue) => void,
): ReactElement {
  return (
    <Form.Check
      type="switch"
      label={field.label}
      checked={value === true}
      onChange={(event: ChangeEvent<HTMLInputElement>) => onChange(field.key, event.target.checked)}
    />
  );
}

function renderSelectField(
  field: PluginSettingsFieldSchema,
  value: PluginFormValue | undefined,
  onChange: (fieldKey: string, nextValue: PluginFormValue) => void,
): ReactElement {
  return (
    <>
      <Form.Label className="small fw-medium">{field.label}</Form.Label>
      <Form.Select
        size="sm"
        value={typeof value === 'string' ? value : ''}
        onChange={(event: ChangeEvent<HTMLSelectElement>) => onChange(field.key, event.target.value)}
      >
        {(field.options ?? []).map((option) => (
          <option key={option.value} value={option.value}>{option.label}</option>
        ))}
      </Form.Select>
    </>
  );
}

function renderPasswordField(
  field: PluginSettingsFieldSchema,
  value: PluginFormValue | undefined,
  passwordVisible: boolean,
  onTogglePassword: (fieldKey: string) => void,
  onChange: (fieldKey: string, nextValue: PluginFormValue) => void,
): ReactElement {
  return (
    <>
      <Form.Label className="small fw-medium">{field.label}</Form.Label>
      <InputGroup size="sm">
        <Form.Control
          type={passwordVisible ? 'text' : 'password'}
          autoComplete="new-password"
          value={toInputValue(value)}
          placeholder={field.placeholder ?? undefined}
          onChange={(event: ChangeEvent<HTMLInputElement>) => onChange(field.key, event.target.value)}
        />
        <Button type="button" variant="secondary" onClick={() => onTogglePassword(field.key)}>
          {passwordVisible ? 'Hide' : 'Show'}
        </Button>
      </InputGroup>
    </>
  );
}

function renderNumberField(
  field: PluginSettingsFieldSchema,
  value: PluginFormValue | undefined,
  onChange: (fieldKey: string, nextValue: PluginFormValue) => void,
): ReactElement {
  return (
    <>
      <Form.Label className="small fw-medium">{field.label}</Form.Label>
      <Form.Control
        type="number"
        size="sm"
        min={field.min ?? undefined}
        max={field.max ?? undefined}
        step={field.step ?? undefined}
        value={toInputValue(value)}
        placeholder={field.placeholder ?? undefined}
        onChange={(event: ChangeEvent<HTMLInputElement>) => {
          const nextRaw = event.target.value;
          if (nextRaw.length === 0) {
            onChange(field.key, null);
            return;
          }
          const nextValue = Number(nextRaw);
          if (!Number.isFinite(nextValue)) {
            return;
          }
          onChange(field.key, nextValue);
        }}
      />
    </>
  );
}

function renderTextField(
  field: PluginSettingsFieldSchema,
  value: PluginFormValue | undefined,
  onChange: (fieldKey: string, nextValue: PluginFormValue) => void,
): ReactElement {
  return (
    <>
      <Form.Label className="small fw-medium">{field.label}</Form.Label>
      <Form.Control
        type={field.type === 'url' ? 'url' : 'text'}
        size="sm"
        value={toInputValue(value)}
        placeholder={field.placeholder ?? undefined}
        onChange={(event: ChangeEvent<HTMLInputElement>) => onChange(field.key, event.target.value)}
      />
    </>
  );
}

function FieldControl({ field, value, passwordVisible, onTogglePassword, onChange }: FieldControlProps): ReactElement {
  switch (field.type) {
    case 'switch':
      return renderSwitchField(field, value, onChange);
    case 'select':
      return renderSelectField(field, value, onChange);
    case 'password':
      return renderPasswordField(field, value, passwordVisible, onTogglePassword, onChange);
    case 'number':
      return renderNumberField(field, value, onChange);
    case 'text':
    case 'url':
      return renderTextField(field, value, onChange);
  }
}

export function PluginSettingsPanel({ schema, runtimeConfig }: PluginSettingsPanelProps): ReactElement {
  const initialValues = useMemo<PluginFormValues>(() => buildInitialValues(schema, runtimeConfig), [schema, runtimeConfig]);
  const [formValues, setFormValues] = useState<PluginFormValues>(initialValues);
  const [visibleSecrets, setVisibleSecrets] = useState<Record<string, boolean>>({});

  const updateTelegram = useUpdateTelegram();
  const updateTools = useUpdateTools();
  const updateVoice = useUpdateVoice();
  const updateRag = useUpdateRag();
  const updateAdvanced = useUpdateAdvanced();

  const isSaving = updateTelegram.isPending
    || updateTools.isPending
    || updateVoice.isPending
    || updateRag.isPending
    || updateAdvanced.isPending;

  const isDirty = useMemo<boolean>(() => {
    return JSON.stringify(formValues) !== JSON.stringify(initialValues);
  }, [formValues, initialValues]);

  const canSave = isKnownPluginSectionKey(schema.sectionKey);

  // Keep local draft aligned with server data after each successful refresh.
  useEffect(() => {
    setFormValues(initialValues);
  }, [initialValues]);

  const handleFieldChange = (fieldKey: string, value: PluginFormValue): void => {
    setFormValues((previous) => ({ ...previous, [fieldKey]: value }));
  };

  const handleToggleSecret = (fieldKey: string): void => {
    setVisibleSecrets((previous) => ({ ...previous, [fieldKey]: !previous[fieldKey] }));
  };

  const handleSave = async (): Promise<void> => {
    if (!canSave) {
      toast.error(`Save handler is not configured for section '${schema.sectionKey}'.`);
      return;
    }

    const nextRuntimeConfig = applyFormValuesToRuntime(schema, runtimeConfig, formValues);

    try {
      switch (schema.sectionKey) {
        case 'telegram':
          await updateTelegram.mutateAsync(nextRuntimeConfig.telegram);
          break;
        case 'tool-browser':
        case 'tool-brave':
        case 'tool-email':
          await updateTools.mutateAsync(nextRuntimeConfig.tools);
          break;
        case 'tool-voice':
        case 'voice-elevenlabs':
        case 'voice-whisper':
          await updateVoice.mutateAsync(nextRuntimeConfig.voice);
          break;
        case 'rag':
          await updateRag.mutateAsync(nextRuntimeConfig.rag);
          break;
        case 'advanced-security':
          await updateAdvanced.mutateAsync({ security: nextRuntimeConfig.security });
          break;
      }
      toast.success(`${schema.pluginName} settings saved`);
    } catch (error: unknown) {
      toast.error(`Failed to save settings: ${extractErrorMessage(error)}`);
    }
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title={schema.pluginName} />
        <p className="text-body-secondary small mb-3">{schema.description}</p>

        <Row className="g-3">
          {schema.fields.map((field) => (
            <Col key={field.key} md={field.type === 'switch' ? 12 : 6}>
              <Form.Group>
                <FieldControl
                  field={field}
                  value={formValues[field.key]}
                  passwordVisible={visibleSecrets[field.key] ?? false}
                  onTogglePassword={handleToggleSecret}
                  onChange={handleFieldChange}
                />
                <Form.Text className="text-muted">{field.help}</Form.Text>
              </Form.Group>
            </Col>
          ))}
        </Row>

        <SettingsSaveBar className="mt-3">
          <Button
            type="button"
            variant="primary"
            size="sm"
            onClick={() => {
              void handleSave();
            }}
            disabled={!isDirty || isSaving || !canSave}
          >
            {isSaving ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
