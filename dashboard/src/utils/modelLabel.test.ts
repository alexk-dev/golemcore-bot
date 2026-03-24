import { describe, expect, it } from 'vitest';
import type { ModelSettings, ModelsConfig } from '../api/models';
import { buildModelTitle, formatModelDisplayLabel } from './modelLabel';

const DEFAULT_MODEL_SETTINGS: ModelSettings = {
  provider: 'openai',
  displayName: null,
  supportsVision: false,
  supportsTemperature: true,
  maxInputTokens: 128000,
  reasoning: null,
};

const MODELS_CONFIG: ModelsConfig = {
  models: {
    'o3-mini': {
      ...DEFAULT_MODEL_SETTINGS,
      displayName: 'OpenAI o3 mini',
    },
    'claude-3-7-sonnet': {
      ...DEFAULT_MODEL_SETTINGS,
      provider: 'anthropic',
      displayName: 'Claude 3.7 Sonnet',
    },
  },
  defaults: DEFAULT_MODEL_SETTINGS,
};

describe('modelLabel', () => {
  it('uses catalog display names for exact and provider-prefixed model ids', () => {
    expect(formatModelDisplayLabel('o3-mini', null, MODELS_CONFIG)).toBe('OpenAI o3 mini');
    expect(formatModelDisplayLabel('openai/o3-mini', 'high', MODELS_CONFIG)).toBe('OpenAI o3 mini:high');
  });

  it('falls back to the raw model slug when the catalog does not know it', () => {
    expect(formatModelDisplayLabel('vendor/custom-model', null, MODELS_CONFIG)).toBe('vendor/custom-model');
    expect(formatModelDisplayLabel(null, null, MODELS_CONFIG)).toBe('Model unavailable');
  });

  it('builds a raw-model tooltip only when display name differs from the slug', () => {
    expect(buildModelTitle('openai/o3-mini', 'high', MODELS_CONFIG)).toBe('openai/o3-mini:high');
    expect(buildModelTitle('vendor/custom-model', null, MODELS_CONFIG)).toBeUndefined();
  });
});
