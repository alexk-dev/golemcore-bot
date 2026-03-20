import type { ModelRouterConfig, TierBinding } from '../api/settings';
import { EXPLICIT_MODEL_TIER_ORDER, type ExplicitModelTierId } from './modelTiers';

function hasText(value: string | null | undefined): value is string {
  return value != null && value.trim().length > 0;
}

export function createEmptyTierBinding(): TierBinding {
  return {
    model: null,
    reasoning: null,
  };
}

export function normalizeTierBinding(binding: TierBinding | null | undefined): TierBinding {
  return {
    model: binding?.model ?? null,
    reasoning: binding?.reasoning ?? null,
  };
}

export function cloneModelRouterConfig(config: ModelRouterConfig): ModelRouterConfig {
  return {
    temperature: config.temperature,
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
  if (hasText(config.routing.model)) {
    models.push(config.routing.model);
  }
  EXPLICIT_MODEL_TIER_ORDER.forEach((tier) => {
    const binding = config.tiers[tier];
    if (hasText(binding?.model)) {
      models.push(binding.model);
    }
  });
  return models;
}
