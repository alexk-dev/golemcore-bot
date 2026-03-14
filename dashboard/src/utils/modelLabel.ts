import type { ModelsConfig } from '../api/models';

function normalizeModelKey(model: string): string {
  const trimmed = model.trim();
  const slashIndex = trimmed.indexOf('/');
  return slashIndex >= 0 ? trimmed.slice(slashIndex + 1) : trimmed;
}

function resolveDisplayName(model: string, modelsConfig: ModelsConfig | null | undefined): string | null {
  if (modelsConfig == null) {
    return null;
  }

  const exact = modelsConfig.models[model];
  if (exact?.displayName != null && exact.displayName.trim().length > 0) {
    return exact.displayName;
  }

  const normalizedModel = normalizeModelKey(model);
  const normalized = modelsConfig.models[normalizedModel];
  if (normalized?.displayName != null && normalized.displayName.trim().length > 0) {
    return normalized.displayName;
  }

  for (const [key, settings] of Object.entries(modelsConfig.models)) {
    if (!model.endsWith(`/${key}`)) {
      continue;
    }
    if (settings.displayName != null && settings.displayName.trim().length > 0) {
      return settings.displayName;
    }
  }

  return null;
}

export function formatModelDisplayLabel(
  model: string | null | undefined,
  reasoning: string | null | undefined,
  modelsConfig: ModelsConfig | null | undefined,
): string {
  if (model == null || model.trim().length === 0) {
    return 'Model unavailable';
  }

  const displayName = resolveDisplayName(model, modelsConfig) ?? model;
  return reasoning != null && reasoning.length > 0 ? `${displayName}:${reasoning}` : displayName;
}

export function buildModelTitle(
  model: string | null | undefined,
  reasoning: string | null | undefined,
  modelsConfig: ModelsConfig | null | undefined,
): string | undefined {
  if (model == null || model.trim().length === 0) {
    return undefined;
  }

  const displayName = resolveDisplayName(model, modelsConfig);
  if (displayName == null || displayName === model) {
    return undefined;
  }

  return reasoning != null && reasoning.length > 0 ? `${model}:${reasoning}` : model;
}
