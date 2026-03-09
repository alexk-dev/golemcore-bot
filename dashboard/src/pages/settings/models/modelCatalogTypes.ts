import type { DiscoveredProviderModel, ModelSettings, ModelsConfig } from '../../../api/models';

const DEFAULT_MAX_INPUT_TOKENS = '128000';

export interface ReasoningLevelDraft {
  level: string;
  maxInputTokens: string;
}

export interface ModelDraft {
  id: string;
  provider: string;
  displayName: string;
  supportsVision: boolean;
  supportsTemperature: boolean;
  maxInputTokens: string;
  reasoningEnabled: boolean;
  reasoningDefault: string;
  reasoningLevels: ReasoningLevelDraft[];
}

export interface GroupedCatalogModels {
  provider: string;
  items: CatalogModelItem[];
}

export interface CatalogModelItem {
  id: string;
  settings: ModelSettings;
}

export function createEmptyModelDraft(defaultProvider = ''): ModelDraft {
  return {
    id: '',
    provider: defaultProvider,
    displayName: '',
    supportsVision: true,
    supportsTemperature: true,
    maxInputTokens: DEFAULT_MAX_INPUT_TOKENS,
    reasoningEnabled: false,
    reasoningDefault: '',
    reasoningLevels: [],
  };
}

export function isBlankModelDraft(draft: ModelDraft): boolean {
  return JSON.stringify(draft) === JSON.stringify(createEmptyModelDraft(draft.provider));
}

export function toModelDraft(id: string, settings: ModelSettings): ModelDraft {
  const reasoningLevels = settings.reasoning != null
    ? Object.entries(settings.reasoning.levels).map(([level, config]) => ({
      level,
      maxInputTokens: String(config.maxInputTokens),
    }))
    : [];

  return {
    id,
    provider: settings.provider,
    displayName: settings.displayName ?? '',
    supportsVision: settings.supportsVision ?? true,
    supportsTemperature: settings.supportsTemperature,
    maxInputTokens: String(settings.maxInputTokens),
    reasoningEnabled: settings.reasoning != null,
    reasoningDefault: settings.reasoning?.default ?? '',
    reasoningLevels,
  };
}

export function toModelSettings(draft: ModelDraft): ModelSettings {
  const maxInputTokens = parsePositiveInt(draft.maxInputTokens, 128000);
  const reasoning = draft.reasoningEnabled
    ? {
      default: draft.reasoningDefault.trim(),
      levels: Object.fromEntries(
        draft.reasoningLevels.map((level) => [
          level.level.trim(),
          { maxInputTokens: parsePositiveInt(level.maxInputTokens, maxInputTokens) },
        ])
      ),
    }
    : null;

  return {
    provider: draft.provider.trim(),
    displayName: emptyToNull(draft.displayName),
    supportsVision: draft.supportsVision,
    supportsTemperature: draft.supportsTemperature,
    maxInputTokens,
    reasoning,
  };
}

export function validateModelDraft(
  draft: ModelDraft,
  existingModels: Record<string, ModelSettings>,
  selectedModelId: string | null,
): string | null {
  const id = draft.id.trim();
  const provider = draft.provider.trim();
  if (id.length === 0) {
    return 'Model ID is required.';
  }
  if (provider.length === 0) {
    return 'Provider is required.';
  }

  const existingId = selectedModelId ?? '';
  if (id !== existingId && Object.prototype.hasOwnProperty.call(existingModels, id)) {
    return `Model "${id}" already exists.`;
  }

  if (!isPositiveIntString(draft.maxInputTokens)) {
    return 'Max input tokens must be a positive integer.';
  }

  if (!draft.reasoningEnabled) {
    return null;
  }

  if (draft.reasoningLevels.length === 0) {
    return 'Add at least one reasoning level or disable reasoning.';
  }

  const seenLevels = new Set<string>();
  for (const level of draft.reasoningLevels) {
    const levelName = level.level.trim();
    if (levelName.length === 0) {
      return 'Reasoning level names cannot be empty.';
    }
    if (seenLevels.has(levelName)) {
      return `Reasoning level "${levelName}" is duplicated.`;
    }
    seenLevels.add(levelName);
    if (!isPositiveIntString(level.maxInputTokens)) {
      return `Reasoning level "${levelName}" must have a positive integer token limit.`;
    }
  }

  if (draft.reasoningDefault.trim().length === 0) {
    return 'Default reasoning level is required when reasoning is enabled.';
  }
  if (!seenLevels.has(draft.reasoningDefault.trim())) {
    return 'Default reasoning level must match one of the configured reasoning levels.';
  }

  return null;
}

export function isModelDraftDirty(
  draft: ModelDraft,
  selectedModelId: string | null,
  modelsConfig: ModelsConfig | null | undefined,
): boolean {
  if (selectedModelId == null) {
    return !isBlankModelDraft(draft);
  }
  const existing = modelsConfig?.models[selectedModelId];
  if (existing == null) {
    return !isBlankModelDraft(draft);
  }
  return JSON.stringify(draft) !== JSON.stringify(toModelDraft(selectedModelId, existing));
}

export function getGroupedCatalogModels(
  modelsConfig: ModelsConfig | null | undefined,
  preferredProviderOrder: string[] = [],
): GroupedCatalogModels[] {
  if (modelsConfig == null) {
    return [];
  }

  const preferredOrder = new Map(preferredProviderOrder.map((provider, index) => [provider, index]));
  const grouped = new Map<string, CatalogModelItem[]>();
  for (const [id, settings] of Object.entries(modelsConfig.models)) {
    const items = grouped.get(settings.provider) ?? [];
    items.push({ id, settings });
    grouped.set(settings.provider, items);
  }

  return Array.from(grouped.entries())
    .sort(([left], [right]) => {
      const leftOrder = preferredOrder.get(left) ?? Number.MAX_SAFE_INTEGER;
      const rightOrder = preferredOrder.get(right) ?? Number.MAX_SAFE_INTEGER;
      if (leftOrder !== rightOrder) {
        return leftOrder - rightOrder;
      }
      return left.localeCompare(right);
    })
    .map(([provider, items]) => ({
      provider,
      items: items.sort((left, right) => {
        const leftLabel = left.settings.displayName ?? left.id;
        const rightLabel = right.settings.displayName ?? right.id;
        return leftLabel.localeCompare(rightLabel);
      }),
    }));
}

export function createDraftFromSuggestion(
  suggestion: DiscoveredProviderModel,
  modelsConfig: ModelsConfig | null | undefined,
): ModelDraft {
  const targetId = resolveSuggestedModelId(suggestion, modelsConfig?.models ?? {});
  const existing = modelsConfig?.models[targetId];
  if (existing != null) {
    return {
      ...toModelDraft(targetId, existing),
    };
  }

  const maxInputTokens = DEFAULT_MAX_INPUT_TOKENS;
  return {
    id: targetId,
    provider: suggestion.provider,
    displayName: suggestion.displayName,
    supportsVision: true,
    supportsTemperature: true,
    maxInputTokens,
    reasoningEnabled: false,
    reasoningDefault: '',
    reasoningLevels: [],
  };
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function isPositiveIntString(value: string): boolean {
  return /^[1-9]\d*$/.test(value.trim());
}

function parsePositiveInt(value: string, fallback: number): number {
  const parsed = Number.parseInt(value.trim(), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function resolveSuggestedModelId(
  suggestion: DiscoveredProviderModel,
  existingModels: Record<string, ModelSettings>,
): string {
  const exactMatch = existingModels[suggestion.id];
  if (exactMatch == null) {
    return suggestion.id;
  }
  if (exactMatch.provider === suggestion.provider) {
    return suggestion.id;
  }

  const providerScopedId = `${suggestion.provider}/${suggestion.id}`;
  if (Object.prototype.hasOwnProperty.call(existingModels, providerScopedId)) {
    return providerScopedId;
  }
  return providerScopedId;
}
