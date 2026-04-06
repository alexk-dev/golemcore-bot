import { describe, expect, it } from 'vitest';
import type { DiscoveredProviderModel, ModelSettings, ModelsConfig } from '../../../api/models';
import { createDraftFromSuggestion } from './modelCatalogTypes';

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
});
