import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { LlmConfig, ModelRouterConfig } from '../../api/settingsTypes';
import ModelsTab from './ModelsTab';

const availableModels = {
  openai: [
    {
      id: 'openai/gpt-5.1',
      displayName: 'GPT-5.1',
      hasReasoning: true,
      reasoningLevels: ['none', 'medium'],
      supportsVision: true,
    },
  ],
  openrouter: [
    {
      id: 'openrouter/qwen/model-name:version',
      displayName: 'Qwen Model',
      hasReasoning: false,
      reasoningLevels: [],
      supportsVision: true,
    },
  ],
};

vi.mock('../../hooks/useSettings', () => ({
  useUpdateModelRouter: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
  }),
}));

vi.mock('../../hooks/useModels', () => ({
  useAvailableModels: () => ({
    data: availableModels,
  }),
}));

const llmConfig: LlmConfig = {
  providers: {
    openai: {
      apiKey: null,
      apiKeyPresent: true,
      baseUrl: null,
      requestTimeoutSeconds: null,
      apiType: 'openai',
      legacyApi: null,
      sourceUrl: null,
      gonkaAddress: null,
      endpoints: [],
    },
    anthropic: {
      apiKey: null,
      apiKeyPresent: false,
      baseUrl: null,
      requestTimeoutSeconds: null,
      apiType: 'anthropic',
      legacyApi: null,
      sourceUrl: null,
      gonkaAddress: null,
      endpoints: [],
    },
    openrouter: {
      apiKey: null,
      apiKeyPresent: true,
      baseUrl: 'https://openrouter.ai/api/v1',
      requestTimeoutSeconds: 30,
      apiType: 'openai',
      legacyApi: null,
      sourceUrl: null,
      gonkaAddress: null,
      endpoints: [],
    },
  },
};

const modelRouterConfig: ModelRouterConfig = {
  temperature: 0.7,
  routing: {
    model: { provider: 'openai', id: 'gpt-5.1' },
    reasoning: 'none',
  },
  tiers: {
    balanced: { model: { provider: 'openai', id: 'gpt-5.1' }, reasoning: 'none' },
    smart: { model: { provider: 'openai', id: 'gpt-5.1' }, reasoning: 'medium' },
    deep: { model: { provider: 'openai', id: 'gpt-5.1' }, reasoning: 'medium' },
    coding: { model: { provider: 'openai', id: 'gpt-5.1' }, reasoning: 'medium' },
    special1: { model: null, reasoning: null },
    special2: { model: null, reasoning: null },
    special3: { model: null, reasoning: null },
    special4: { model: null, reasoning: null },
    special5: { model: null, reasoning: null },
  },
  dynamicTierEnabled: true,
};

describe('ModelsTab', () => {
  beforeEach(() => {
    modelRouterConfig.tiers.special1 = { model: null, reasoning: null };
  });

  it('renders optional special tiers with an explicit empty model option', () => {
    const html = renderToStaticMarkup(
      <ModelsTab config={modelRouterConfig} llmConfig={llmConfig} />,
    );

    expect(html.match(/Not configured/g)?.length ?? 0).toBe(5);
  });

  it('keeps unavailable configured special tiers visible instead of pretending they are empty', () => {
    modelRouterConfig.tiers.special1 = { model: { provider: 'anthropic', id: 'claude-sonnet-4' }, reasoning: null };

    const html = renderToStaticMarkup(
      <ModelsTab config={modelRouterConfig} llmConfig={llmConfig} />,
    );

    expect(html).toContain('anthropic (unavailable)');
    expect(html).toContain('claude-sonnet-4 (unavailable)');
  });

  it('hides the implicit openrouter prefix in routing model selects', () => {
    modelRouterConfig.routing = {
      model: { provider: 'openrouter', id: 'qwen/model-name:version' },
      reasoning: null,
    };

    const html = renderToStaticMarkup(
      <ModelsTab config={modelRouterConfig} llmConfig={llmConfig} />,
    );

    expect(html).toContain('qwen/model-name:version');
    expect(html).not.toContain('openrouter/qwen/model-name:version');
  });
});
