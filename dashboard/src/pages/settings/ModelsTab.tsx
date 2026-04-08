import { type ReactElement, useEffect, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import type { HiveStatusResponse } from '../../api/hive';
import type { AvailableModel } from '../../api/models';
import { modelReferenceFromSpec, modelReferenceToSpec, type LlmConfig, type ModelRouterConfig } from '../../api/settings';
import { useUpdateModelRouter } from '../../hooks/useSettings';
import { useAvailableModels } from '../../hooks/useModels';
import { toEditorModelIdForProvider } from '../../lib/providerModelIds';
import { cloneModelRouterConfig, getTierBinding, updateTierBinding } from '../../lib/modelRouter';
import {
  allowsEmptyModelSelection,
  EXPLICIT_MODEL_TIER_ORDER,
  MODEL_TIER_META,
  type ExplicitModelTierId,
} from '../../lib/modelTiers';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import { Badge, Button, Card, Col, Form, Row } from '../../lib/react-bootstrap';
import { HiveManagedPolicyNotice } from './HiveManagedPolicyNotice';
import { getHiveManagedPolicyDetails } from './hiveManagedPolicySupport';

interface ModelsTabProps {
  config: ModelRouterConfig;
  llmConfig: LlmConfig;
  hiveStatus?: HiveStatusResponse | null;
}

interface TierCardConfig {
  key: ExplicitModelTierId;
  label: string;
  color: string;
  allowEmptyModel: boolean;
}

interface TierModelCardProps {
  label: string;
  color: string;
  providers: Record<string, AvailableModel[]>;
  providerNames: string[];
  modelProvider: string;
  modelValue: string;
  reasoningValue: string;
  allowEmptyModel: boolean;
  onModelChange: (value: string, providerName: string) => void;
  onReasoningChange: (value: string) => void;
}

const EMPTY_AVAILABLE_MODELS: Record<string, AvailableModel[]> = {};

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

function TierModelCard({
  label,
  color,
  providers,
  providerNames,
  modelProvider,
  modelValue,
  reasoningValue,
  allowEmptyModel,
  onModelChange,
  onReasoningChange,
}: TierModelCardProps): ReactElement {
  const configuredProvider = useMemo(() => {
    if (modelValue.length === 0) {
      return '';
    }
    for (const [providerName, models] of Object.entries(providers)) {
      if (models.some((model) => toEditorModelIdForProvider(model.id, providerName) === modelValue)) {
        return providerName;
      }
    }
    if (modelProvider.length > 0) {
      return modelProvider;
    }
    const delimiterIndex = modelValue.indexOf('/');
    return delimiterIndex > 0 ? modelValue.slice(0, delimiterIndex) : '';
  }, [modelProvider, modelValue, providers]);

  const providerOptionValues = useMemo(() => {
    if (configuredProvider.length === 0 || providerNames.includes(configuredProvider)) {
      return providerNames;
    }
    return [configuredProvider, ...providerNames];
  }, [configuredProvider, providerNames]);

  const selectedProvider = useMemo(() => {
    if (modelValue.length === 0) {
      return allowEmptyModel ? '' : (providerNames[0] ?? '');
    }
    return configuredProvider.length > 0 ? configuredProvider : (providerNames[0] ?? '');
  }, [allowEmptyModel, configuredProvider, modelValue, providerNames]);

  const [provider, setProvider] = useState(selectedProvider);

  useEffect(() => {
    setProvider(selectedProvider);
  }, [selectedProvider]);

  const modelsForProvider = useMemo(() => {
    const providerModels = (providers[provider] ?? []).map((model) => {
      const editorId = toEditorModelIdForProvider(model.id, provider);
      return {
        ...model,
        editorId,
        displayLabel: model.displayName ?? editorId,
      };
    });
    if (modelValue.length === 0 || providerModels.some((model) => model.editorId === modelValue)) {
      return providerModels;
    }
    return [
      {
        id: modelValue,
        editorId: modelValue,
        displayLabel: `${modelValue} (unavailable)`,
        hasReasoning: false,
        reasoningLevels: [],
        supportsVision: false,
      },
      ...providerModels,
    ];
  }, [modelValue, provider, providers]);
  const selectedModel = modelsForProvider.find((model) => model.editorId === modelValue);
  const reasoningLevels = selectedModel?.reasoningLevels ?? [];
  const hasProviders = providerOptionValues.length > 0;
  const providerUnavailable = configuredProvider.length > 0 && !providerNames.includes(configuredProvider);

  // Keep optional special tiers unconfigured until the user explicitly selects a model.
  useEffect(() => {
    if (!allowEmptyModel && modelValue.length === 0 && modelsForProvider.length > 0) {
      onModelChange(modelsForProvider[modelsForProvider.length - 1].id, provider);
    }
  }, [allowEmptyModel, modelValue, modelsForProvider, onModelChange, provider]);

  return (
    <Card className="tier-card h-100">
      <Card.Body className="p-3">
        <Badge bg={color} className="mb-2">{label}</Badge>

        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium mb-1">Provider</Form.Label>
          <Form.Select
            size="sm"
            value={provider}
            disabled={!hasProviders}
            onChange={(e) => {
              const nextProvider = e.target.value;
              setProvider(nextProvider);
              onModelChange('', nextProvider);
            }}
          >
            {allowEmptyModel && <option value="">Select provider</option>}
            {!hasProviders && <option value="">No providers</option>}
            {providerOptionValues.map((providerName) => (
              <option key={providerName || 'empty'} value={providerName}>
                {providerName}
                {providerUnavailable && providerName === configuredProvider ? ' (unavailable)' : ''}
              </option>
            ))}
          </Form.Select>
        </Form.Group>

        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium mb-1">Model</Form.Label>
          <Form.Select
            size="sm"
            value={modelValue}
            disabled={!hasProviders && modelsForProvider.length === 0}
            onChange={(e) => onModelChange(e.target.value, provider)}
          >
            {allowEmptyModel && <option value="">Not configured</option>}
            {modelsForProvider.map((model) => (
              <option key={`${provider}-${model.id}`} value={model.editorId}>{model.displayLabel}</option>
            ))}
          </Form.Select>
        </Form.Group>

        {reasoningLevels.length > 0 && (
          <Form.Group>
            <Form.Label className="small fw-medium mb-1">Reasoning</Form.Label>
            <Form.Select size="sm" value={reasoningValue} onChange={(e) => onReasoningChange(e.target.value)}>
              <option value="">Default</option>
              {reasoningLevels.map((level) => (
                <option key={level} value={level}>{level}</option>
              ))}
            </Form.Select>
          </Form.Group>
        )}
      </Card.Body>
    </Card>
  );
}

export default function ModelsTab({ config, llmConfig, hiveStatus }: ModelsTabProps): ReactElement {
  const updateRouter = useUpdateModelRouter();
  const { data: available } = useAvailableModels();
  const [form, setForm] = useState<ModelRouterConfig>(cloneModelRouterConfig(config));

  useEffect(() => {
    setForm(cloneModelRouterConfig(config));
  }, [config]);

  const readyProviderNames = useMemo(
    () => Object.entries(llmConfig.providers ?? {})
      .filter(([, cfg]) => cfg.apiKeyPresent === true)
      .map(([name]) => name),
    [llmConfig],
  );

  const providers = useMemo(() => {
    if (available == null) {
      return EMPTY_AVAILABLE_MODELS;
    }
    const readySet = new Set(readyProviderNames);
    return Object.fromEntries(
      Object.entries(available).filter(([providerName]) => readySet.has(providerName)),
    );
  }, [available, readyProviderNames]);

  const providerNames = useMemo(() => Object.keys(providers), [providers]);
  const isModelsDirty = useMemo(() => hasDiff(form, config), [form, config]);
  const managedPolicy = getHiveManagedPolicyDetails(hiveStatus);

  const handleSave = async (): Promise<void> => {
    await updateRouter.mutateAsync(form);
    toast.success('Model router settings saved');
  };

  const tierCards: TierCardConfig[] = EXPLICIT_MODEL_TIER_ORDER.map((tier) => ({
    key: tier,
    label: MODEL_TIER_META[tier].label,
    color: MODEL_TIER_META[tier].settingsCardColor,
    allowEmptyModel: allowsEmptyModelSelection(tier),
  }));

  return (
    <>
      {managedPolicy ? (
        <HiveManagedPolicyNotice policy={managedPolicy} sectionLabel="Model Router" className="mb-3" />
      ) : null}

      <fieldset disabled={managedPolicy != null} className="border-0 m-0 p-0">
        <Card className="settings-card mb-3">
          <Card.Body>
            <SettingsCardTitle title="Global Settings" />
            <Row className="g-3">
              <Col md={6}>
                <Form.Group>
                  <Form.Label className="small fw-medium">
                    Temperature: {form.temperature?.toFixed(1) ?? '0.7'}
                    <HelpTip text="Controls randomness of LLM responses. Higher = more creative, lower = more deterministic. Ignored by reasoning models." />
                  </Form.Label>
                  <Form.Range
                    min={0}
                    max={2}
                    step={0.1}
                    value={form.temperature ?? 0.7}
                    onChange={(e) => setForm({ ...form, temperature: parseFloat(e.target.value) })}
                  />
                </Form.Group>
              </Col>
              <Col md={6} className="d-flex align-items-end">
                <Form.Check
                  type="switch"
                  label={<>Dynamic tier upgrade <HelpTip text="Automatically upgrade to coding tier when code-related activity is detected mid-conversation" /></>}
                  checked={form.dynamicTierEnabled ?? true}
                  onChange={(e) => setForm({ ...form, dynamicTierEnabled: e.target.checked })}
                />
              </Col>
            </Row>
          </Card.Body>
        </Card>

        <Row className="g-3 mb-3">
          {providerNames.length === 0 && (
            <Col xs={12}>
              <Card className="settings-card">
                <Card.Body className="py-2">
                  <small className="text-body-secondary">
                    No LLM providers with API keys configured. Add a provider with an API key in the LLM Providers tab to select models here.
                  </small>
                </Card.Body>
              </Card>
            </Col>
          )}

          <Col sm={6} lg={3}>
            <TierModelCard
              label="Routing"
              color="dark"
              providers={providers}
              providerNames={providerNames}
              modelProvider={form.routing.model?.provider ?? ''}
              modelValue={toEditorModelIdForProvider(
                modelReferenceToSpec(form.routing.model),
                form.routing.model?.provider,
              )}
              reasoningValue={form.routing.reasoning ?? ''}
              allowEmptyModel={false}
              onModelChange={(value, providerName) => setForm({
                ...form,
                routing: {
                  model: modelReferenceFromSpec(value, providerName),
                  reasoning: null,
                },
              })}
              onReasoningChange={(value) => setForm({
                ...form,
                routing: {
                  ...form.routing,
                  reasoning: toNullableString(value),
                },
              })}
            />
          </Col>
          {tierCards.map(({ key, label, color, allowEmptyModel }) => (
            <Col sm={6} lg={4} xl={3} key={key}>
              <TierModelCard
                label={label}
                color={color}
                providers={providers}
                providerNames={providerNames}
                modelProvider={getTierBinding(form, key).model?.provider ?? ''}
                modelValue={toEditorModelIdForProvider(
                  modelReferenceToSpec(getTierBinding(form, key).model),
                  getTierBinding(form, key).model?.provider,
                )}
                reasoningValue={getTierBinding(form, key).reasoning ?? ''}
                allowEmptyModel={allowEmptyModel}
                onModelChange={(value, providerName) => setForm(updateTierBinding(form, key, {
                  model: modelReferenceFromSpec(value, providerName),
                  reasoning: null,
                }))}
                onReasoningChange={(value) => setForm(updateTierBinding(form, key, {
                  ...getTierBinding(form, key),
                  reasoning: toNullableString(value),
                }))}
              />
            </Col>
          ))}
        </Row>

        <SettingsSaveBar>
          <Button
            type="button"
            variant="primary"
            size="sm"
            onClick={() => { void handleSave(); }}
            disabled={managedPolicy != null || !isModelsDirty || updateRouter.isPending}
          >
            {updateRouter.isPending ? 'Saving...' : 'Save Model Configuration'}
          </Button>
          <SaveStateHint isDirty={managedPolicy == null && isModelsDirty} />
        </SettingsSaveBar>
      </fieldset>
    </>
  );
}
