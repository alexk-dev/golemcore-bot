import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { LlmConfig, ModelRouterConfig } from '../../api/settings';
import ModelsTab from './ModelsTab';

vi.mock('../../hooks/useSettings', () => ({
  useUpdateModelRouter: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
  }),
}));

vi.mock('../../hooks/useModels', () => ({
  useAvailableModels: () => ({
    data: {
      openai: [
        {
          id: 'openai/gpt-5.1',
          displayName: 'GPT-5.1',
          hasReasoning: true,
          reasoningLevels: ['none', 'medium'],
          supportsVision: true,
        },
      ],
    },
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
  it('renders optional special tiers with an explicit empty model option', () => {
    const html = renderToStaticMarkup(
      <ModelsTab config={modelRouterConfig} llmConfig={llmConfig} />,
    );

    expect(html.match(/Not configured/g)?.length ?? 0).toBe(5);
  });
});
