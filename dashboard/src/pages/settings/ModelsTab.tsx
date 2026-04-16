import { type ReactElement, useEffect, useMemo, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import { useNavigate } from 'react-router-dom';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import type { HiveStatusResponse } from '../../api/hive';
import type { AvailableModel } from '../../api/models';
import { modelReferenceFromSpec, modelReferenceToSpec } from '../../api/settings';
import type { LlmConfig, ModelRouterConfig } from '../../api/settingsTypes';
import { useUpdateModelRouter } from '../../hooks/useSettings';
import { useAvailableModels } from '../../hooks/useModels';
import { useBeforeUnloadGuard } from '../../hooks/useBeforeUnloadGuard';
import { toEditorModelIdForProvider } from '../../lib/providerModelIds';
import { cloneModelRouterConfig, getTierBinding, updateTierBinding } from '../../lib/modelRouter';
import {
  allowsEmptyModelSelection,
  EXPLICIT_MODEL_TIER_ORDER,
  MODEL_TIER_META,
  type DisplayModelTierId,
  type ExplicitModelTierId,
} from '../../lib/modelTiers';
import {
  hasRouterEditorDiff,
  resolveTemperatureAfterModelChange,
  toNullableRouterString,
} from './modelFallbacksEditorSupport';
import { TierModelCard } from './models/TierModelCard';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import ConfirmModal from '../../components/common/ConfirmModal';
import { Button, Card, Col, Form, Row } from '../../components/ui/tailwind-components';
import { HiveManagedPolicyNotice } from './HiveManagedPolicyNotice';
import { getHiveManagedPolicyDetails } from './hiveManagedPolicySupport';
import { UNSAVED_CHANGES_MESSAGE, UNSAVED_CHANGES_TITLE } from './unsavedChangesCopy';

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

const EMPTY_AVAILABLE_MODELS: Record<string, AvailableModel[]> = {};
const TIER_CARDS: TierCardConfig[] = EXPLICIT_MODEL_TIER_ORDER.map((tier) => ({
  key: tier,
  label: MODEL_TIER_META[tier].label,
  color: MODEL_TIER_META[tier].settingsCardColor,
  allowEmptyModel: allowsEmptyModelSelection(tier),
}));

export default function ModelsTab({ config, llmConfig, hiveStatus }: ModelsTabProps): ReactElement {
  const navigate = useNavigate();
  const updateRouter = useUpdateModelRouter();
  const { data: available } = useAvailableModels();
  const [form, setForm] = useState<ModelRouterConfig>(cloneModelRouterConfig(config));
  const configSourceRef = useRef(config);
  const serverSnapshotRef = useRef<ModelRouterConfig>(cloneModelRouterConfig(config));
  const [pendingTier, setPendingTier] = useState<DisplayModelTierId | null>(null);

  useEffect(() => {
    // Sync clean forms to refreshed runtime config without clobbering local unsaved edits.
    if (config === configSourceRef.current) {
      return;
    }
    const previousSnapshot = serverSnapshotRef.current;
    const nextSnapshot = cloneModelRouterConfig(config);
    configSourceRef.current = config;
    serverSnapshotRef.current = nextSnapshot;
    if (!hasRouterEditorDiff(form, previousSnapshot)) {
      setForm(nextSnapshot);
    }
  }, [config, form]);

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
  const isModelsDirty = useMemo(() => hasRouterEditorDiff(form, config), [form, config]);
  const managedPolicy = getHiveManagedPolicyDetails(hiveStatus);

  useBeforeUnloadGuard(managedPolicy == null && isModelsDirty);

  const handleSave = async (): Promise<void> => {
    const savedForm = cloneModelRouterConfig(form);
    await updateRouter.mutateAsync(savedForm);
    serverSnapshotRef.current = savedForm;
    setForm(savedForm);
    toast.success('Model router settings saved');
  };

  const handleConfigureFallbacks = (tier: DisplayModelTierId): void => {
    if (isModelsDirty) {
      setPendingTier(tier);
      return;
    }
    navigate(`/settings/models/${tier}`);
  };

  const handleConfirmLeave = (): void => {
    if (pendingTier != null) {
      navigate(`/settings/models/${pendingTier}`);
    }
    setPendingTier(null);
  };

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
              temperatureValue={form.routing.temperature}
              fallbackCount={form.routing.fallbacks.length}
              allowEmptyModel={false}
              onModelChange={(value, providerName) => setForm({
                ...form,
                routing: {
                  model: modelReferenceFromSpec(value, providerName),
                  reasoning: null,
                  temperature: resolveTemperatureAfterModelChange(form.routing.temperature, value, providerName, providers),
                  fallbackMode: form.routing.fallbackMode,
                  fallbacks: form.routing.fallbacks,
                },
              })}
              onReasoningChange={(value) => setForm({
                ...form,
                routing: {
                  ...form.routing,
                  reasoning: toNullableRouterString(value),
                },
              })}
              onTemperatureChange={(value) => setForm({
                ...form,
                routing: {
                  ...form.routing,
                  temperature: value,
                },
              })}
              onConfigureFallbacks={() => handleConfigureFallbacks('routing')}
            />
          </Col>
          {TIER_CARDS.map(({ key, label, color, allowEmptyModel }) => (
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
                temperatureValue={getTierBinding(form, key).temperature}
                fallbackCount={getTierBinding(form, key).fallbacks.length}
                allowEmptyModel={allowEmptyModel}
                onModelChange={(value, providerName) => setForm(updateTierBinding(form, key, {
                  model: modelReferenceFromSpec(value, providerName),
                  reasoning: null,
                  temperature: resolveTemperatureAfterModelChange(getTierBinding(form, key).temperature, value, providerName, providers),
                  fallbackMode: getTierBinding(form, key).fallbackMode,
                  fallbacks: getTierBinding(form, key).fallbacks,
                }))}
                onReasoningChange={(value) => setForm(updateTierBinding(form, key, {
                  ...getTierBinding(form, key),
                  reasoning: toNullableRouterString(value),
                }))}
                onTemperatureChange={(value) => setForm(updateTierBinding(form, key, {
                  ...getTierBinding(form, key),
                  temperature: value,
                }))}
                onConfigureFallbacks={() => handleConfigureFallbacks(key)}
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

      <ConfirmModal
        show={pendingTier != null}
        title={UNSAVED_CHANGES_TITLE}
        message={UNSAVED_CHANGES_MESSAGE}
        confirmLabel="Leave"
        confirmVariant="warning"
        onConfirm={handleConfirmLeave}
        onCancel={() => setPendingTier(null)}
      />
    </>
  );
}
