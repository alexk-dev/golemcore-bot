import { type ReactElement, useEffect, useMemo, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import { useNavigate, useParams } from 'react-router-dom';

import { useBeforeUnloadGuard } from '../hooks/useBeforeUnloadGuard';
import { useHiveStatus } from '../hooks/useHive';
import { useAvailableModels } from '../hooks/useModels';
import { useRuntimeConfig, useUpdateModelRouter } from '../hooks/useSettings';
import type { AvailableModel } from '../api/models';
import type { FallbackMode, ModelRouterConfig, TierBinding } from '../api/settingsTypes';
import {
  cloneModelRouterConfig,
  createEmptyTierBinding,
  getTierBinding,
  normalizeFallbackMode,
  normalizeTierBinding,
  updateTierBinding,
} from '../lib/modelRouter';
import {
  isExplicitModelTier,
  MODEL_TIER_META,
  type DisplayModelTierId,
} from '../lib/modelTiers';
import { hasRouterEditorDiff, normalizeModelFallbacks } from './settings/modelFallbacksEditorSupport';
import { DEFAULT_FALLBACK_WEIGHT, FallbackRow, MAX_FALLBACKS } from './settings/models/FallbackRow';
import { HiveManagedPolicyNotice } from './settings/HiveManagedPolicyNotice';
import { getHiveManagedPolicyDetails } from './settings/hiveManagedPolicySupport';
import { Badge, Button, Card, Form, Placeholder, Spinner } from '../components/ui/tailwind-components';
import ConfirmModal from '../components/common/ConfirmModal';
import SettingsCardTitle from '../components/common/SettingsCardTitle';
import { SaveStateHint, SettingsSaveBar } from '../components/common/SettingsSaveBar';
import { UNSAVED_CHANGES_MESSAGE, UNSAVED_CHANGES_TITLE } from './settings/unsavedChangesCopy';

const EMPTY_AVAILABLE_MODELS: Record<string, AvailableModel[]> = {};

function resolveTierFromParam(value: string | undefined): DisplayModelTierId | null {
  if (value == null) {
    return null;
  }
  const normalized = value.trim().toLowerCase();
  if (normalized === 'routing') {
    return 'routing';
  }
  return isExplicitModelTier(normalized) ? normalized : null;
}

function getBindingForTier(config: ModelRouterConfig, tier: DisplayModelTierId): TierBinding {
  if (tier === 'routing') {
    return normalizeTierBinding(config.routing);
  }
  return getTierBinding(config, tier);
}

function setBindingForTier(
  config: ModelRouterConfig,
  tier: DisplayModelTierId,
  binding: TierBinding,
): ModelRouterConfig {
  if (tier === 'routing') {
    return { ...config, routing: normalizeTierBinding(binding) };
  }
  return updateTierBinding(config, tier, binding);
}

function ensureWeightedFallbacks(binding: TierBinding): TierBinding {
  return {
    ...binding,
    fallbacks: normalizeModelFallbacks(binding.fallbacks.map((fallback) => ({
      ...fallback,
      weight: fallback.weight ?? DEFAULT_FALLBACK_WEIGHT,
    }))),
  };
}

function updateFallbackMode(binding: TierBinding, mode: FallbackMode): TierBinding {
  const nextBinding = { ...binding, fallbackMode: mode };
  return mode === 'weighted' ? ensureWeightedFallbacks(nextBinding) : nextBinding;
}

function TierFallbackLoadingCard(): ReactElement {
  return (
    <Card className="settings-card">
      <Card.Body>
        <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={8} /></Placeholder>
        <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={12} /></Placeholder>
        <div className="d-flex justify-content-center pt-2">
          <Spinner animation="border" size="sm" />
        </div>
      </Card.Body>
    </Card>
  );
}

interface TierFallbackEditorCardProps {
  tier: DisplayModelTierId;
  binding: TierBinding;
  providers: Record<string, AvailableModel[]>;
  providerNames: string[];
  onChange: (binding: TierBinding) => void;
}

function TierFallbackEditorCard({
  tier,
  binding,
  providers,
  providerNames,
  onChange,
}: TierFallbackEditorCardProps): ReactElement {
  const handleAddFallback = (): void => {
    onChange({
      ...binding,
      fallbacks: normalizeModelFallbacks([
        ...binding.fallbacks,
        { model: null, reasoning: null, temperature: null, weight: DEFAULT_FALLBACK_WEIGHT },
      ]),
    });
  };

  return (
    <Card className="settings-card mb-3">
      <Card.Body>
        <SettingsCardTitle title="Fallback routing" />
        <div className="d-flex align-items-end justify-content-between gap-2 mt-2">
          <div className="small text-body-secondary">
            When the primary model fails, the agent falls back to one of the configured models using the selected mode.
          </div>
          <Form.Group>
            <Form.Label className="small fw-medium mb-1">Fallback mode</Form.Label>
            <Form.Select
              size="sm"
              value={binding.fallbackMode}
              onChange={(event) => onChange({
                ...updateFallbackMode(binding, normalizeFallbackMode(event.target.value)),
              })}
            >
              <option value="sequential">Sequential</option>
              <option value="round_robin">Round robin</option>
              <option value="weighted">Weighted</option>
            </Form.Select>
          </Form.Group>
        </div>

        <div className="d-flex justify-content-between align-items-center mt-3 mb-2">
          <span className="small fw-medium">Fallback models ({binding.fallbacks.length}/{MAX_FALLBACKS})</span>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            disabled={binding.fallbacks.length >= MAX_FALLBACKS}
            onClick={handleAddFallback}
          >
            Add fallback
          </Button>
        </div>
        <div className="d-grid gap-2">
          {binding.fallbacks.length === 0 && (
            <small className="text-body-secondary">No fallback models configured for this tier.</small>
          )}
          {binding.fallbacks.map((fallback, index) => (
            <FallbackRow
              key={`${tier}-${index}`}
              fallback={fallback}
              fallbackIndex={index}
              providers={providers}
              providerNames={providerNames}
              isWeightedMode={binding.fallbackMode === 'weighted'}
              onChange={(nextFallback) => onChange({
                ...binding,
                fallbacks: normalizeModelFallbacks(binding.fallbacks.map((item, itemIndex) => (
                  itemIndex === index ? nextFallback : item
                ))),
              })}
              onRemove={() => onChange({
                ...binding,
                fallbacks: normalizeModelFallbacks(binding.fallbacks.filter((_, itemIndex) => itemIndex !== index)),
              })}
            />
          ))}
        </div>
      </Card.Body>
    </Card>
  );
}

export default function TierFallbacksPage(): ReactElement {
  const navigate = useNavigate();
  const { tier: tierParam } = useParams<{ tier?: string }>();
  const tier = resolveTierFromParam(tierParam);

  const { data: rc, isLoading: rcLoading } = useRuntimeConfig();
  const { data: available } = useAvailableModels();
  const hiveStatus = useHiveStatus();
  const updateRouter = useUpdateModelRouter();

  const [form, setForm] = useState<ModelRouterConfig | null>(
    () => (rc != null ? cloneModelRouterConfig(rc.modelRouter) : null),
  );
  const configSourceRef = useRef<ModelRouterConfig | null>(rc?.modelRouter ?? null);
  const serverSnapshotRef = useRef<ModelRouterConfig | null>(
    rc != null ? cloneModelRouterConfig(rc.modelRouter) : null,
  );
  const [showLeaveConfirm, setShowLeaveConfirm] = useState(false);

  useEffect(() => {
    if (rc != null) {
      // Sync clean forms to refreshed runtime config without clobbering local unsaved edits.
      if (rc.modelRouter === configSourceRef.current) {
        return;
      }
      const previousSnapshot = serverSnapshotRef.current;
      const nextSnapshot = cloneModelRouterConfig(rc.modelRouter);
      configSourceRef.current = rc.modelRouter;
      serverSnapshotRef.current = nextSnapshot;
      const hasDirtyEdits = form != null
        && previousSnapshot != null
        && hasRouterEditorDiff(form, previousSnapshot);
      if (!hasDirtyEdits) {
        setForm(nextSnapshot);
      }
    }
  }, [form, rc]);

  const readyProviderNames = useMemo(() => {
    if (rc == null) {
      return [];
    }
    return Object.entries(rc.llm.providers ?? {})
      .filter(([, cfg]) => cfg.apiKeyPresent === true)
      .map(([name]) => name);
  }, [rc]);

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
  const isDirty = useMemo(
    () => (form != null && rc != null ? hasRouterEditorDiff(form, rc.modelRouter) : false),
    [form, rc],
  );
  const managedPolicy = getHiveManagedPolicyDetails(hiveStatus.data);

  useBeforeUnloadGuard(managedPolicy == null && isDirty);

  const handleBack = (): void => {
    if (isDirty) {
      setShowLeaveConfirm(true);
      return;
    }
    navigate('/settings/models');
  };

  const handleConfirmLeave = (): void => {
    setShowLeaveConfirm(false);
    navigate('/settings/models');
  };

  if (tier == null) {
    return (
      <div>
        <div className="page-header">
          <h4>Tier not found</h4>
          <p className="text-body-secondary mb-0">Unknown model tier in URL.</p>
        </div>
        <div className="mb-3">
          <Button type="button" variant="secondary" size="sm" onClick={() => navigate('/settings/models')}>
            Back to Model Router
          </Button>
        </div>
      </div>
    );
  }

  const tierMeta = MODEL_TIER_META[tier];
  const binding = form != null ? getBindingForTier(form, tier) : createEmptyTierBinding();

  const updateBinding = (nextBinding: TierBinding): void => {
    if (form == null) {
      return;
    }
    setForm(setBindingForTier(form, tier, nextBinding));
  };

  const handleSave = async (): Promise<void> => {
    if (form == null) {
      return;
    }
    const savedForm = cloneModelRouterConfig(form);
    await updateRouter.mutateAsync(savedForm);
    serverSnapshotRef.current = savedForm;
    setForm(savedForm);
    toast.success(`${tierMeta.label} fallback settings saved`);
  };

  return (
    <div>
      <div className="page-header">
        <h4>
          <Badge bg={tierMeta.settingsCardColor} className="me-2">{tierMeta.label}</Badge>
          Fallback models
        </h4>
        <p className="text-body-secondary mb-0">
          Up to {MAX_FALLBACKS} fallback models with ordering mode. Primary model is configured on the Model Router page.
        </p>
      </div>

      <div className="mb-3">
        <Button type="button" variant="secondary" size="sm" onClick={handleBack}>
          Back to Model Router
        </Button>
      </div>

      {managedPolicy ? (
        <HiveManagedPolicyNotice policy={managedPolicy} sectionLabel="Model Router" className="mb-3" />
      ) : null}

      {rcLoading || form == null ? (
        <TierFallbackLoadingCard />
      ) : (
        <fieldset disabled={managedPolicy != null} className="border-0 m-0 p-0">
          <TierFallbackEditorCard
            tier={tier}
            binding={binding}
            providers={providers}
            providerNames={providerNames}
            onChange={updateBinding}
          />
          <SettingsSaveBar>
            <Button
              type="button"
              variant="primary"
              size="sm"
              onClick={() => { void handleSave(); }}
              disabled={managedPolicy != null || !isDirty || updateRouter.isPending}
            >
              {updateRouter.isPending ? 'Saving...' : 'Save Fallback Settings'}
            </Button>
            <SaveStateHint isDirty={managedPolicy == null && isDirty} />
          </SettingsSaveBar>
        </fieldset>
      )}

      <ConfirmModal
        show={showLeaveConfirm}
        title={UNSAVED_CHANGES_TITLE}
        message={UNSAVED_CHANGES_MESSAGE}
        confirmLabel="Leave"
        confirmVariant="warning"
        onConfirm={handleConfirmLeave}
        onCancel={() => setShowLeaveConfirm(false)}
      />
    </div>
  );
}
