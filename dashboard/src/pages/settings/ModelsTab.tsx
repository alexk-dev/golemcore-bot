import { type ReactElement, useEffect, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import type { AvailableModel } from '../../api/models';
import { useUpdateModelRouter } from '../../hooks/useSettings';
import type { LlmConfig, ModelRouterConfig } from '../../api/settings';
import { useAvailableModels } from '../../hooks/useModels';
import { cloneModelRouterConfig, getTierBinding, updateTierBinding } from '../../lib/modelRouter';
import { EXPLICIT_MODEL_TIER_ORDER, MODEL_TIER_META, type ExplicitModelTierId } from '../../lib/modelTiers';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import { Badge, Button, Card, Col, Form, Row } from '../../lib/react-bootstrap';

interface ModelsTabProps {
  config: ModelRouterConfig;
  llmConfig: LlmConfig;
}

interface TierCardConfig {
  key: ExplicitModelTierId;
  label: string;
  color: string;
}

interface TierModelCardProps {
  label: string;
  color: string;
  providers: Record<string, AvailableModel[]>;
  providerNames: string[];
  modelValue: string;
  reasoningValue: string;
  onModelChange: (value: string) => void;
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
  modelValue,
  reasoningValue,
  onModelChange,
  onReasoningChange,
}: TierModelCardProps): ReactElement {
  const selectedProvider = useMemo(() => {
    if (modelValue.length === 0) {
      return providerNames[0] ?? '';
    }
    for (const [providerName, models] of Object.entries(providers)) {
      if (models.some((model) => model.id === modelValue)) {
        return providerName;
      }
    }
    return providerNames[0] ?? '';
  }, [modelValue, providers, providerNames]);

  const [provider, setProvider] = useState(selectedProvider);

  useEffect(() => {
    setProvider(selectedProvider);
  }, [selectedProvider]);

  const modelsForProvider = useMemo(() => providers[provider] ?? [], [providers, provider]);
  const selectedModel = modelsForProvider.find((model) => model.id === modelValue);
  const reasoningLevels = selectedModel?.reasoningLevels ?? [];
  const hasProviders = providerNames.length > 0;

  // Auto-select minimum (last) model when no explicit model is configured
  useEffect(() => {
    if (modelValue.length === 0 && modelsForProvider.length > 0) {
      onModelChange(modelsForProvider[modelsForProvider.length - 1].id);
    }
  }, [modelValue, modelsForProvider, onModelChange]);

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
              setProvider(e.target.value);
              onModelChange('');
            }}
          >
            {!hasProviders && <option value="">No providers</option>}
            {providerNames.map((providerName) => (
              <option key={providerName} value={providerName}>{providerName}</option>
            ))}
          </Form.Select>
        </Form.Group>

        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium mb-1">Model</Form.Label>
          <Form.Select size="sm" value={modelValue} disabled={!hasProviders} onChange={(e) => onModelChange(e.target.value)}>
            {modelsForProvider.map((model) => (
              <option key={model.id} value={model.id}>{model.displayName ?? model.id}</option>
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

export default function ModelsTab({ config, llmConfig }: ModelsTabProps): ReactElement {
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

  const handleSave = async (): Promise<void> => {
    await updateRouter.mutateAsync(form);
    toast.success('Model router settings saved');
  };

  const tierCards: TierCardConfig[] = EXPLICIT_MODEL_TIER_ORDER.map((tier) => ({
    key: tier,
    label: MODEL_TIER_META[tier].label,
    color: MODEL_TIER_META[tier].settingsCardColor,
  }));

  return (
    <>
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
            modelValue={form.routing.model ?? ''}
            reasoningValue={form.routing.reasoning ?? ''}
            onModelChange={(value) => setForm({
              ...form,
              routing: {
                model: toNullableString(value),
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
        {tierCards.map(({ key, label, color }) => (
          <Col sm={6} lg={4} xl={3} key={key}>
            <TierModelCard
              label={label}
              color={color}
              providers={providers}
              providerNames={providerNames}
              modelValue={getTierBinding(form, key).model ?? ''}
              reasoningValue={getTierBinding(form, key).reasoning ?? ''}
              onModelChange={(value) => setForm(updateTierBinding(form, key, {
                model: toNullableString(value),
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
        <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isModelsDirty || updateRouter.isPending}>
          {updateRouter.isPending ? 'Saving...' : 'Save Model Configuration'}
        </Button>
        <SaveStateHint isDirty={isModelsDirty} />
      </SettingsSaveBar>
    </>
  );
}
