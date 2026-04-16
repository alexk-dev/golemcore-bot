import { type ReactElement, useEffect, useMemo, useState } from 'react';
import toast from 'react-hot-toast';

import type { HiveStatusResponse } from '../../api/hive';
import type { AvailableModel } from '../../api/models';
import { modelReferenceFromSpec, modelReferenceToSpec } from '../../api/settings';
import type { LlmConfig, ModelRouterConfig, TierBinding, TierFallback } from '../../api/settingsTypes';
import { useAvailableModels } from '../../hooks/useModels';
import { useUpdateModelRouter } from '../../hooks/useSettings';
import { cloneModelRouterConfig, getTierBinding, updateTierBinding } from '../../lib/modelRouter';
import {
  buildModelsForProvider,
  hasModelFallbackEditorDiff,
  normalizeModelFallbacks,
  toNullableModelFallbackString,
} from './modelFallbacksEditorSupport';
import { EXPLICIT_MODEL_TIER_ORDER, MODEL_TIER_META, type ExplicitModelTierId } from '../../lib/modelTiers';
import { toEditorModelIdForProvider } from '../../lib/providerModelIds';
import { Badge, Button, Card, Col, Form, Row } from '../../components/ui/tailwind-components';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { HiveManagedPolicyNotice } from './HiveManagedPolicyNotice';
import { getHiveManagedPolicyDetails } from './hiveManagedPolicySupport';

interface ModelFallbacksTabProps {
  config: ModelRouterConfig;
  llmConfig: LlmConfig;
  hiveStatus?: HiveStatusResponse | null;
}

interface FallbackEditorProps {
  tier: ExplicitModelTierId;
  binding: TierBinding;
  providers: Record<string, AvailableModel[]>;
  providerNames: string[];
  onChange: (binding: TierBinding) => void;
}

interface FallbackRowProps {
  fallback: TierFallback;
  fallbackIndex: number;
  providers: Record<string, AvailableModel[]>;
  providerNames: string[];
  onChange: (fallback: TierFallback) => void;
  onRemove: () => void;
}

interface ModelSelectFieldsProps {
  modelValue: string;
  modelProvider: string;
  reasoningValue: string;
  temperatureValue: number | null;
  providers: Record<string, AvailableModel[]>;
  providerNames: string[];
  onModelChange: (value: string, providerName: string) => void;
  onReasoningChange: (value: string) => void;
  onTemperatureChange: (value: number | null) => void;
}

const EMPTY_AVAILABLE_MODELS: Record<string, AvailableModel[]> = {};
const DEFAULT_TEMPERATURE = 0.7;
const MAX_FALLBACKS = 5;

function ModelSelectFields({
  modelValue,
  modelProvider,
  reasoningValue,
  temperatureValue,
  providers,
  providerNames,
  onModelChange,
  onReasoningChange,
  onTemperatureChange,
}: ModelSelectFieldsProps): ReactElement {
  const selectedProvider = modelProvider.length > 0 ? modelProvider : (providerNames[0] ?? '');
  const [provider, setProvider] = useState(selectedProvider);

  useEffect(() => {
    // Keep the local provider select synchronized with saved router state.
    setProvider(selectedProvider);
  }, [selectedProvider]);

  const modelsForProvider = useMemo(
    () => buildModelsForProvider(providers, provider, modelValue),
    [modelValue, provider, providers],
  );
  const selectedModel = modelsForProvider.find((model) => model.editorId === modelValue);
  const reasoningLevels = selectedModel?.reasoningLevels ?? [];
  const supportsTemperature = selectedModel?.supportsTemperature !== false;

  return (
    <Row className="g-2 align-items-end">
      <Col md={3}>
        <Form.Group>
          <Form.Label className="small fw-medium mb-1">Provider</Form.Label>
          <Form.Select
            size="sm"
            value={provider}
            onChange={(event) => {
              const nextProvider = event.target.value;
              setProvider(nextProvider);
              onModelChange('', nextProvider);
            }}
          >
            {providerNames.length === 0 && <option value="">No providers</option>}
            {providerNames.map((providerName) => <option key={providerName} value={providerName}>{providerName}</option>)}
          </Form.Select>
        </Form.Group>
      </Col>
      <Col md={4}>
        <Form.Group>
          <Form.Label className="small fw-medium mb-1">Model</Form.Label>
          <Form.Select size="sm" value={modelValue} onChange={(event) => onModelChange(event.target.value, provider)}>
            <option value="">Not configured</option>
            {modelsForProvider.map((model) => <option key={`${provider}-${model.id}`} value={model.editorId}>{model.displayLabel}</option>)}
          </Form.Select>
        </Form.Group>
      </Col>
      <Col md={2}>
        <Form.Group>
          <Form.Label className="small fw-medium mb-1">Reasoning</Form.Label>
          <Form.Select
            size="sm"
            value={reasoningValue}
            disabled={reasoningLevels.length === 0}
            onChange={(event) => onReasoningChange(event.target.value)}
          >
            <option value="">Default</option>
            {reasoningLevels.map((level) => <option key={level} value={level}>{level}</option>)}
          </Form.Select>
        </Form.Group>
      </Col>
      <Col md={3}>
        <Form.Group>
          <Form.Label className="small fw-medium mb-1">
            Temperature: {supportsTemperature ? (temperatureValue ?? DEFAULT_TEMPERATURE).toFixed(1) : 'not supported'}
          </Form.Label>
          <Form.Range
            min={0}
            max={2}
            step={0.1}
            value={temperatureValue ?? DEFAULT_TEMPERATURE}
            disabled={!supportsTemperature}
            onChange={(event) => onTemperatureChange(parseFloat(event.target.value))}
          />
        </Form.Group>
      </Col>
    </Row>
  );
}

function FallbackRow({
  fallback,
  fallbackIndex,
  providers,
  providerNames,
  onChange,
  onRemove,
}: FallbackRowProps): ReactElement {
  return (
    <Card className="settings-card bg-body-tertiary">
      <Card.Body className="p-3">
        <div className="d-flex justify-content-between align-items-center mb-2">
          <span className="small fw-medium">Fallback {fallbackIndex + 1}</span>
          <Button type="button" variant="secondary" size="sm" onClick={onRemove}>Remove</Button>
        </div>
        <ModelSelectFields
          modelProvider={fallback.model?.provider ?? ''}
          modelValue={toEditorModelIdForProvider(modelReferenceToSpec(fallback.model), fallback.model?.provider)}
          reasoningValue={fallback.reasoning ?? ''}
          temperatureValue={fallback.temperature}
          providers={providers}
          providerNames={providerNames}
          onModelChange={(value, providerName) => onChange({ ...fallback, model: modelReferenceFromSpec(value, providerName), reasoning: null })}
          onReasoningChange={(value) => onChange({ ...fallback, reasoning: toNullableModelFallbackString(value) })}
          onTemperatureChange={(value) => onChange({ ...fallback, temperature: value })}
        />
      </Card.Body>
    </Card>
  );
}

function FallbackEditor({ tier, binding, providers, providerNames, onChange }: FallbackEditorProps): ReactElement {
  const tierMeta = MODEL_TIER_META[tier];
  return (
    <Card className="settings-card mb-3">
      <Card.Body>
        <div className="d-flex align-items-start justify-content-between gap-2 mb-3">
          <div>
            <Badge bg={tierMeta.settingsCardColor} className="mb-2">{tierMeta.label}</Badge>
            <div className="small text-body-secondary">Primary and up to {MAX_FALLBACKS} fallback models for this tier.</div>
          </div>
          <Form.Group>
            <Form.Label className="small fw-medium mb-1">Fallback mode</Form.Label>
            <Form.Select
              size="sm"
              value={binding.fallbackMode}
              onChange={(event) => onChange({ ...binding, fallbackMode: event.target.value === 'random' ? 'random' : 'sequential' })}
            >
              <option value="sequential">Sequential 1-&gt;5</option>
              <option value="random">Random fallback</option>
            </Form.Select>
          </Form.Group>
        </div>

        <ModelSelectFields
          modelProvider={binding.model?.provider ?? ''}
          modelValue={toEditorModelIdForProvider(modelReferenceToSpec(binding.model), binding.model?.provider)}
          reasoningValue={binding.reasoning ?? ''}
          temperatureValue={binding.temperature}
          providers={providers}
          providerNames={providerNames}
          onModelChange={(value, providerName) => onChange({ ...binding, model: modelReferenceFromSpec(value, providerName), reasoning: null })}
          onReasoningChange={(value) => onChange({ ...binding, reasoning: toNullableModelFallbackString(value) })}
          onTemperatureChange={(value) => onChange({ ...binding, temperature: value })}
        />

        <div className="d-flex justify-content-between align-items-center mt-3 mb-2">
          <span className="small fw-medium">Fallback models ({binding.fallbacks.length}/{MAX_FALLBACKS})</span>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            disabled={binding.fallbacks.length >= MAX_FALLBACKS}
            onClick={() => onChange({ ...binding, fallbacks: normalizeModelFallbacks([...binding.fallbacks, { model: null, reasoning: null, temperature: null }]) })}
          >
            Add fallback
          </Button>
        </div>
        <div className="d-grid gap-2">
          {binding.fallbacks.length === 0 && <small className="text-body-secondary">No fallback models configured for this tier.</small>}
          {binding.fallbacks.map((fallback, index) => (
            <FallbackRow
              key={`${tier}-${index}`}
              fallback={fallback}
              fallbackIndex={index}
              providers={providers}
              providerNames={providerNames}
              onChange={(nextFallback) => onChange({ ...binding, fallbacks: normalizeModelFallbacks(binding.fallbacks.map((item, itemIndex) => (itemIndex === index ? nextFallback : item))) })}
              onRemove={() => onChange({ ...binding, fallbacks: normalizeModelFallbacks(binding.fallbacks.filter((_, itemIndex) => itemIndex !== index)) })}
            />
          ))}
        </div>
      </Card.Body>
    </Card>
  );
}

export default function ModelFallbacksTab({ config, llmConfig, hiveStatus }: ModelFallbacksTabProps): ReactElement {
  const updateRouter = useUpdateModelRouter();
  const { data: available } = useAvailableModels();
  const [form, setForm] = useState<ModelRouterConfig>(cloneModelRouterConfig(config));
  const managedPolicy = getHiveManagedPolicyDetails(hiveStatus);

  useEffect(() => {
    // Reset the editor when runtime config is refreshed after save.
    setForm(cloneModelRouterConfig(config));
  }, [config]);

  const readyProviderNames = useMemo(
    () => Object.entries(llmConfig.providers ?? {}).filter(([, cfg]) => cfg.apiKeyPresent === true).map(([name]) => name),
    [llmConfig],
  );
  const providers = useMemo(() => {
    if (available == null) {
      return EMPTY_AVAILABLE_MODELS;
    }
    const readySet = new Set(readyProviderNames);
    return Object.fromEntries(Object.entries(available).filter(([providerName]) => readySet.has(providerName)));
  }, [available, readyProviderNames]);
  const providerNames = useMemo(() => Object.keys(providers), [providers]);
  const isDirty = useMemo(() => hasModelFallbackEditorDiff(form, config), [form, config]);

  const handleSave = async (): Promise<void> => {
    await updateRouter.mutateAsync(form);
    toast.success('Fallback routing settings saved');
  };

  return (
    <>
      {managedPolicy ? <HiveManagedPolicyNotice policy={managedPolicy} sectionLabel="Model Router" className="mb-3" /> : null}
      <fieldset disabled={managedPolicy != null} className="border-0 m-0 p-0">
        <Card className="settings-card mb-3">
          <Card.Body>
            <SettingsCardTitle title="Tier fallback routing" />
            <p className="text-body-secondary small mb-0">
              Configure primary temperature and up to five fallback models per tier. This stores settings only; agent-loop fallback execution is intentionally out of scope.
            </p>
          </Card.Body>
        </Card>
        {EXPLICIT_MODEL_TIER_ORDER.map((tier) => (
          <FallbackEditor
            key={tier}
            tier={tier}
            binding={getTierBinding(form, tier)}
            providers={providers}
            providerNames={providerNames}
            onChange={(binding) => setForm(updateTierBinding(form, tier, binding))}
          />
        ))}
        <SettingsSaveBar>
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={managedPolicy != null || !isDirty || updateRouter.isPending}>
            {updateRouter.isPending ? 'Saving...' : 'Save Fallback Settings'}
          </Button>
          <SaveStateHint isDirty={managedPolicy == null && isDirty} />
        </SettingsSaveBar>
      </fieldset>
    </>
  );
}
