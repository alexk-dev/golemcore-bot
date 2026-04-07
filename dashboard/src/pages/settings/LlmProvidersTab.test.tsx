import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { LlmConfig, ModelRouterConfig } from '../../api/settings';
import LlmProvidersTab from './LlmProvidersTab';

vi.mock('../../hooks/useSettings', () => ({
  useAddLlmProvider: () => ({ isPending: false, mutateAsync: vi.fn() }),
  useUpdateLlmProvider: () => ({ isPending: false, mutateAsync: vi.fn() }),
  useRemoveLlmProvider: () => ({ isPending: false, mutateAsync: vi.fn() }),
}));

const config: LlmConfig = {
  providers: {
    openrouter: {
      apiKey: null,
      apiKeyPresent: true,
      baseUrl: 'https://openrouter.ai/api/v1',
      requestTimeoutSeconds: 30,
      apiType: 'openai',
      legacyApi: null,
    },
  },
};

const modelRouter: ModelRouterConfig = {
  temperature: 0.7,
  routing: {
    model: { provider: 'openrouter', id: 'openai/gpt-5' },
    reasoning: null,
  },
  tiers: {
    balanced: { model: { provider: 'openrouter', id: 'openai/gpt-5' }, reasoning: null },
    smart: { model: null, reasoning: null },
    deep: { model: null, reasoning: null },
    coding: { model: null, reasoning: null },
    special1: { model: null, reasoning: null },
    special2: { model: null, reasoning: null },
    special3: { model: null, reasoning: null },
    special4: { model: null, reasoning: null },
    special5: { model: null, reasoning: null },
  },
  dynamicTierEnabled: true,
};

describe('LlmProvidersTab', () => {
  it('renders provider input as combobox and keeps openrouter suggestion visible in the page', () => {
    const html = renderToStaticMarkup(
      <LlmProvidersTab config={config} modelRouter={modelRouter} />,
    );

    expect(html).toContain('LLM Providers');
    expect(html).toContain('autocomplete-combobox__control');
    expect(html).toContain('autocomplete-combobox__trigger');
    expect(html).toContain('role="combobox"');
    expect(html).toContain('openrouter');
    expect(html).toContain('Add Provider');
  });
});
