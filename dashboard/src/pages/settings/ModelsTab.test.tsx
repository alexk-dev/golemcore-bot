import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { LlmConfig, ModelRouterConfig } from '../../api/settings';
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
    },
    anthropic: {
      apiKey: null,
      apiKeyPresent: false,
      baseUrl: null,
      requestTimeoutSeconds: null,
      apiType: 'anthropic',
    },
  },
};

const modelRouterConfig: ModelRouterConfig = {
  temperature: 0.7,
  routing: {
    model: 'openai/gpt-5.1',
    reasoning: 'none',
  },
  tiers: {
    balanced: { model: 'openai/gpt-5.1', reasoning: 'none' },
    smart: { model: 'openai/gpt-5.1', reasoning: 'medium' },
    deep: { model: 'openai/gpt-5.1', reasoning: 'medium' },
    coding: { model: 'openai/gpt-5.1', reasoning: 'medium' },
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
    modelRouterConfig.tiers.special1 = { model: 'anthropic/claude-sonnet-4', reasoning: null };

    const html = renderToStaticMarkup(
      <ModelsTab config={modelRouterConfig} llmConfig={llmConfig} />,
    );

    expect(html).toContain('anthropic (unavailable)');
    expect(html).toContain('anthropic/claude-sonnet-4 (unavailable)');
  });
});
