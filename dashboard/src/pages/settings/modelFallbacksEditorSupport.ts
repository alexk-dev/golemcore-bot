import type { AvailableModel } from '../../api/models';
import type { TierFallback } from '../../api/settingsTypes';
import { toEditorModelIdForProvider } from '../../lib/providerModelIds';

export interface ModelFallbackEditorModel extends AvailableModel {
  editorId: string;
  displayLabel: string;
}

export function normalizeModelFallbacks(fallbacks: TierFallback[]): TierFallback[] {
  return fallbacks.slice(0, 5).map((fallback) => ({
    model: fallback.model != null ? { ...fallback.model } : null,
    reasoning: fallback.reasoning ?? null,
    temperature: fallback.temperature ?? null,
  }));
}

export function hasModelFallbackEditorDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

export function toNullableModelFallbackString(value: string): string | null {
  return value.length > 0 ? value : null;
}

export function buildModelProviderOptions(providerNames: string[], configuredProvider: string): string[] {
  const values = new Set<string>();
  if (configuredProvider.length > 0) {
    values.add(configuredProvider);
  }
  providerNames.forEach((providerName) => values.add(providerName));
  return Array.from(values);
}

export function resolveTemperatureAfterModelChange(
  currentTemperature: number | null,
  newEditorId: string,
  provider: string,
  providers: Record<string, AvailableModel[]>,
): number | null {
  if (newEditorId.length === 0) {
    return currentTemperature;
  }
  const candidate = (providers[provider] ?? []).find((model) => {
    return toEditorModelIdForProvider(model.id, provider) === newEditorId;
  });
  if (candidate == null) {
    return currentTemperature;
  }
  return candidate.supportsTemperature ? currentTemperature : null;
}

export function buildModelsForProvider(
  providers: Record<string, AvailableModel[]>,
  provider: string,
  modelValue: string,
): ModelFallbackEditorModel[] {
  const providerModels = (providers[provider] ?? []).map((model) => {
    const editorId = toEditorModelIdForProvider(model.id, provider);
    return { ...model, editorId, displayLabel: model.displayName ?? editorId };
  });
  if (modelValue.length === 0 || providerModels.some((model) => model.editorId === modelValue)) {
    return providerModels;
  }
  const unavailableProviderOption = buildUnavailableProviderModel(provider, modelValue);
  return unavailableProviderOption == null ? providerModels : [unavailableProviderOption, ...providerModels];
}

function buildUnavailableProviderModel(provider: string, modelValue: string): ModelFallbackEditorModel | null {
  if (provider.length === 0) {
    return null;
  }
  return {
    id: modelValue,
    displayName: `${modelValue} (unavailable)`,
    editorId: modelValue,
    displayLabel: `${modelValue} (unavailable)`,
    hasReasoning: false,
    reasoningLevels: [],
    supportsVision: false,
    supportsTemperature: true,
  };
}
