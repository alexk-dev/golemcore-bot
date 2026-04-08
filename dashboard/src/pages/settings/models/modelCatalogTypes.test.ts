import { describe, expect, it } from 'vitest';
import type { DiscoveredProviderModel, ModelSettings, ModelsConfig } from '../../../api/models';
import {
  canTestModelDraft,
  createDraftFromSuggestion,
  createEmptyModelDraft,
  resolvePersistedModelId,
  toModelDraft,
  toPersistedModelId,
  validateModelDraft,
} from './modelCatalogTypes';

const suggestion: DiscoveredProviderModel = {
  provider: 'openrouter',
  id: 'openai/gpt-4o',
  displayName: 'GPT-4o',
  ownedBy: 'openai',
  defaultSettings: null,
};

describe('createDraftFromSuggestion', () => {
  it('uses resolved registry defaults when available', () => {
    const resolvedDefaults: ModelSettings = {
      provider: 'ignored',
      displayName: 'OpenRouter GPT-4o',
      supportsVision: true,
      supportsTemperature: false,
      maxInputTokens: 200000,
      reasoning: {
        default: 'medium',
        levels: {
          medium: {
            maxInputTokens: 200000,
          },
        },
      },
    };

    const draft = createDraftFromSuggestion(suggestion, null, resolvedDefaults);

    expect(draft.id).toBe('openai/gpt-4o');
    expect(draft.provider).toBe('openrouter');
    expect(draft.displayName).toBe('OpenRouter GPT-4o');
    expect(draft.supportsTemperature).toBe(false);
    expect(draft.maxInputTokens).toBe('200000');
    expect(draft.reasoningEnabled).toBe(true);
    expect(draft.reasoningDefault).toBe('medium');
    expect(draft.reasoningLevels).toEqual([{ level: 'medium', maxInputTokens: '200000' }]);
  });

  it('falls back to built-in defaults when resolved settings are missing', () => {
    const draft = createDraftFromSuggestion(suggestion, null, null);

    expect(draft.id).toBe('openai/gpt-4o');
    expect(draft.provider).toBe('openrouter');
    expect(draft.displayName).toBe('GPT-4o');
    expect(draft.supportsVision).toBe(true);
    expect(draft.supportsTemperature).toBe(true);
    expect(draft.maxInputTokens).toBe('128000');
    expect(draft.reasoningEnabled).toBe(false);
  });

  it('keeps the existing catalog model when the suggestion already exists', () => {
    const existingModels: ModelsConfig = {
      defaults: {
        provider: 'openai',
        displayName: null,
        supportsVision: true,
        supportsTemperature: true,
        maxInputTokens: 128000,
        reasoning: null,
      },
      models: {
        'openai/gpt-4o': {
          provider: 'openrouter',
          displayName: 'Pinned GPT-4o',
          supportsVision: false,
          supportsTemperature: false,
          maxInputTokens: 64000,
          reasoning: null,
        },
      },
    };

    const draft = createDraftFromSuggestion(suggestion, existingModels, {
      provider: 'ignored',
      displayName: 'Should not win',
      supportsVision: true,
      supportsTemperature: true,
      maxInputTokens: 200000,
      reasoning: null,
    });

    expect(draft.id).toBe('openai/gpt-4o');
    expect(draft.displayName).toBe('Pinned GPT-4o');
    expect(draft.supportsVision).toBe(false);
    expect(draft.supportsTemperature).toBe(false);
    expect(draft.maxInputTokens).toBe('64000');
  });

  it('shows raw openrouter ids in the editor while reserving a prefixed persisted id', () => {
    const openRouterSuggestion: DiscoveredProviderModel = {
      provider: 'openrouter',
      id: 'qwen/model-name:version',
      displayName: 'Qwen Model',
      ownedBy: 'qwen',
      defaultSettings: null,
    };

    const draft = createDraftFromSuggestion(openRouterSuggestion, null, null);

    expect(draft.id).toBe('qwen/model-name:version');
    expect(toPersistedModelId(draft)).toBe('openrouter/qwen/model-name:version');
  });

  it('keeps provider-scoped persisted ids for collisions while showing raw id in editor', () => {
    const collidingSuggestion: DiscoveredProviderModel = {
      provider: 'anthropic',
      id: 'gpt-4o',
      displayName: 'Anthropic GPT-4o',
      ownedBy: 'anthropic',
      defaultSettings: null,
    };
    const existingModels: ModelsConfig = {
      defaults: {
        provider: 'openai',
        displayName: null,
        supportsVision: true,
        supportsTemperature: true,
        maxInputTokens: 128000,
        reasoning: null,
      },
      models: {
        'gpt-4o': {
          provider: 'openai',
          displayName: 'OpenAI GPT-4o',
          supportsVision: true,
          supportsTemperature: true,
          maxInputTokens: 128000,
          reasoning: null,
        },
      },
    };

    const draft = createDraftFromSuggestion(collidingSuggestion, existingModels, null);

    expect(draft.id).toBe('gpt-4o');
    expect(toPersistedModelId(draft)).toBe('anthropic/gpt-4o');
  });
});

describe('openrouter implicit prefix handling', () => {
  it('hides the persisted openrouter prefix when loading an existing catalog model', () => {
    const draft = toModelDraft('openrouter/qwen/model-name:version', {
      provider: 'openrouter',
      displayName: 'Qwen Model',
      supportsVision: true,
      supportsTemperature: true,
      maxInputTokens: 128000,
      reasoning: null,
    });

    expect(draft.id).toBe('qwen/model-name:version');
    expect(draft.provider).toBe('openrouter');
  });

  it('checks duplicates against the canonical persisted openrouter id', () => {
    const draft = {
      ...createEmptyModelDraft('openrouter'),
      id: 'qwen/model-name:version',
      displayName: 'Qwen Model',
    };
    const existingModels: ModelsConfig['models'] = {
      'openrouter/qwen/model-name:version': {
        provider: 'openrouter',
        displayName: 'Existing Qwen Model',
        supportsVision: true,
        supportsTemperature: true,
        maxInputTokens: 128000,
        reasoning: null,
      },
    };

    expect(validateModelDraft(draft, existingModels, null)).toBe(
      'Model "openrouter/qwen/model-name:version" already exists.',
    );
  });

  it('migrates an edited legacy openrouter model to the canonical persisted id', () => {
    const draft = {
      ...createEmptyModelDraft('openrouter'),
      id: 'qwen/model-name:version',
      displayName: 'Qwen Model',
    };
    const existingModels: ModelsConfig['models'] = {
      'qwen/model-name:version': {
        provider: 'openrouter',
        displayName: 'Legacy Qwen Model',
        supportsVision: true,
        supportsTemperature: true,
        maxInputTokens: 128000,
        reasoning: null,
      },
    };

    expect(resolvePersistedModelId(draft, existingModels, 'qwen/model-name:version')).toBe(
      'openrouter/qwen/model-name:version',
    );
  });
});

describe('canTestModelDraft', () => {
  it('returns false for unsaved drafts', () => {
    const draft = {
      ...createEmptyModelDraft('google'),
      id: 'gemini-2.0-flash',
    };

    expect(canTestModelDraft(draft, false, false)).toBe(false);
  });

  it('returns false for dirty existing drafts', () => {
    const draft = {
      ...createEmptyModelDraft('google'),
      id: 'gemini-2.5-flash',
    };

    expect(canTestModelDraft(draft, true, true)).toBe(false);
  });

  it('returns true for clean persisted models with provider and id', () => {
    const draft = {
      ...createEmptyModelDraft('google'),
      id: 'gemini-2.5-flash',
    };

    expect(canTestModelDraft(draft, true, false)).toBe(true);
  });
});
