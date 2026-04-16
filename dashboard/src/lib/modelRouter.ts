import { modelReferenceToSpec } from '../api/settings';
import type { ModelRouterConfig, TierBinding, TierFallback } from '../api/settingsTypes';
import { EXPLICIT_MODEL_TIER_ORDER, type ExplicitModelTierId } from './modelTiers';

function hasText(value: string | null | undefined): value is string {
  return value != null && value.trim().length > 0;
}

export function createEmptyTierBinding(): TierBinding {
  return {
    model: null,
    reasoning: null,
    temperature: null,
    fallbackMode: 'sequential',
    fallbacks: [],
  };
}

function normalizeFallback(fallback: TierFallback): TierFallback {
  return {
    model: fallback.model != null ? { ...fallback.model } : null,
    reasoning: fallback.reasoning ?? null,
    temperature: fallback.temperature ?? null,
  };
}

export function normalizeTierBinding(binding: TierBinding | null | undefined): TierBinding {
  return {
    model: binding?.model != null ? { ...binding.model } : null,
    reasoning: binding?.reasoning ?? null,
    temperature: binding?.temperature ?? null,
    fallbackMode: binding?.fallbackMode === 'random' ? 'random' : 'sequential',
    fallbacks: binding?.fallbacks.map(normalizeFallback).slice(0, 5) ?? [],
  };
}

export function cloneModelRouterConfig(config: ModelRouterConfig): ModelRouterConfig {
  return {
    routing: normalizeTierBinding(config.routing),
    tiers: {
      balanced: normalizeTierBinding(config.tiers.balanced),
      smart: normalizeTierBinding(config.tiers.smart),
      deep: normalizeTierBinding(config.tiers.deep),
      coding: normalizeTierBinding(config.tiers.coding),
      special1: normalizeTierBinding(config.tiers.special1),
      special2: normalizeTierBinding(config.tiers.special2),
      special3: normalizeTierBinding(config.tiers.special3),
      special4: normalizeTierBinding(config.tiers.special4),
      special5: normalizeTierBinding(config.tiers.special5),
    },
    dynamicTierEnabled: config.dynamicTierEnabled,
  };
}

export function getTierBinding(config: ModelRouterConfig, tier: ExplicitModelTierId): TierBinding {
  return normalizeTierBinding(config.tiers[tier]);
}

export function updateTierBinding(
  config: ModelRouterConfig,
  tier: ExplicitModelTierId,
  nextBinding: TierBinding,
): ModelRouterConfig {
  return {
    ...config,
    tiers: {
      ...config.tiers,
      [tier]: normalizeTierBinding(nextBinding),
    },
  };
}

export function listConfiguredModelSpecs(config: ModelRouterConfig): string[] {
  const models: string[] = [];
  const routingModel = modelReferenceToSpec(config.routing.model);
  if (hasText(routingModel)) {
    models.push(routingModel);
  }
  config.routing.fallbacks.forEach((fallback) => {
    const fallbackModel = modelReferenceToSpec(fallback.model);
    if (hasText(fallbackModel)) {
      models.push(fallbackModel);
    }
  });
  EXPLICIT_MODEL_TIER_ORDER.forEach((tier) => {
    const binding = config.tiers[tier];
    const model = modelReferenceToSpec(binding?.model);
    if (hasText(model)) {
      models.push(model);
    }
    binding?.fallbacks.forEach((fallback) => {
      const fallbackModel = modelReferenceToSpec(fallback.model);
      if (hasText(fallbackModel)) {
        models.push(fallbackModel);
      }
    });
  });
  return models;
}
