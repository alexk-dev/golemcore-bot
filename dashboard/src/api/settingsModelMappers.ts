import type { FallbackMode, ModelReference, ModelRegistryConfig, ModelRouterConfig, TierBinding, TierFallback } from './settingsTypes';
import { toNullableString, type UnknownRecord } from './settingsUtils';

export function modelReferenceToSpec(model: ModelReference | null | undefined): string | null {
  if (model == null) {
    return null;
  }
  const id = toNullableString(model.id);
  if (id == null) {
    return null;
  }
  const provider = toNullableString(model.provider);
  if (provider == null || id.startsWith(`${provider}/`)) {
    return id;
  }
  return `${provider}/${id}`;
}

export function modelReferenceFromSpec(
  modelSpec: string | null | undefined,
  providerHint: string | null = null,
): ModelReference | null {
  const normalizedModelSpec = toNullableString(modelSpec);
  if (normalizedModelSpec == null) {
    return null;
  }
  const normalizedProvider = toNullableString(providerHint);
  if (normalizedProvider != null && normalizedModelSpec.startsWith(`${normalizedProvider}/`)) {
    return { provider: normalizedProvider, id: normalizedModelSpec.slice(normalizedProvider.length + 1) };
  }
  return { provider: normalizedProvider, id: normalizedModelSpec };
}

export function toModelRegistryConfig(value: unknown): ModelRegistryConfig {
  const record = value != null && typeof value === 'object' ? value as UnknownRecord : {};
  return { repositoryUrl: toNullableString(record.repositoryUrl), branch: toNullableString(record.branch) ?? 'main' };
}

function toModelReference(value: unknown): ModelReference | null {
  if (typeof value === 'string') {
    return modelReferenceFromSpec(value);
  }
  if (value == null || typeof value !== 'object') {
    return null;
  }
  const record = value as UnknownRecord;
  const provider = toNullableString(record.provider);
  const id = toNullableString(record.id);
  if (provider == null && id == null) {
    return null;
  }
  return { provider, id };
}

function toFallbackMode(value: unknown): FallbackMode {
  return typeof value === 'string' && value.toLowerCase() === 'random' ? 'random' : 'sequential';
}

function toTemperature(value: unknown): number | null {
  return typeof value === 'number' ? value : null;
}

function toTierFallback(value: unknown): TierFallback | null {
  if (value == null || typeof value !== 'object') {
    return null;
  }
  const record = value as UnknownRecord;
  const model = toModelReference(record.model);
  if (model == null) {
    return null;
  }
  return { model, reasoning: toNullableString(record.reasoning), temperature: toTemperature(record.temperature) };
}

function toTierFallbacks(value: unknown): TierFallback[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((entry) => {
    const fallback = toTierFallback(entry);
    return fallback == null ? [] : [fallback];
  }).slice(0, 5);
}

function toTierBinding(value: unknown): TierBinding {
  if (value == null || typeof value !== 'object') {
    return { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] };
  }
  const record = value as UnknownRecord;
  return {
    model: toModelReference(record.model),
    reasoning: toNullableString(record.reasoning),
    temperature: toTemperature(record.temperature),
    fallbackMode: toFallbackMode(record.fallbackMode),
    fallbacks: toTierFallbacks(record.fallbacks),
  };
}

export function toModelRouterConfig(value: unknown): ModelRouterConfig {
  const record = value != null && typeof value === 'object' ? value as UnknownRecord : {};
  const rawTiers = record.tiers != null && typeof record.tiers === 'object' ? record.tiers as Record<string, unknown> : {};
  return {
    routing: toTierBinding(record.routing),
    tiers: {
      balanced: toTierBinding(rawTiers.balanced),
      smart: toTierBinding(rawTiers.smart),
      deep: toTierBinding(rawTiers.deep),
      coding: toTierBinding(rawTiers.coding),
      special1: toTierBinding(rawTiers.special1),
      special2: toTierBinding(rawTiers.special2),
      special3: toTierBinding(rawTiers.special3),
      special4: toTierBinding(rawTiers.special4),
      special5: toTierBinding(rawTiers.special5),
    },
    dynamicTierEnabled: typeof record.dynamicTierEnabled === 'boolean' ? record.dynamicTierEnabled : null,
  };
}
