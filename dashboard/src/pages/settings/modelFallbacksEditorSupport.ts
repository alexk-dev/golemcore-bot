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
  return [{
    id: modelValue,
    displayName: `${modelValue} (unavailable)`,
    editorId: modelValue,
    displayLabel: `${modelValue} (unavailable)`,
    hasReasoning: false,
    reasoningLevels: [],
    supportsVision: false,
    supportsTemperature: true,
  }, ...providerModels];
}
