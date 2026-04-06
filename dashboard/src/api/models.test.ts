import { beforeEach, describe, expect, it, vi } from 'vitest';

const clientGetMock = vi.fn();

vi.mock('./client', () => ({
  default: {
    get: clientGetMock,
  },
}));

describe('models api', () => {
  beforeEach(() => {
    clientGetMock.mockReset();
  });

  it('returns discovered provider models including direct default settings payload', async () => {
    clientGetMock.mockResolvedValue({
      data: [{
        provider: 'openrouter',
        id: 'openai/gpt-5',
        displayName: 'OpenAI: GPT-5',
        ownedBy: 'openai',
        defaultSettings: {
          provider: 'openrouter',
          displayName: 'OpenAI: GPT-5',
          supportsVision: true,
          supportsTemperature: false,
          maxInputTokens: 400000,
          reasoning: null,
        },
      }],
    });

    const api = await import('./models');
    const discoveredModels = await api.discoverProviderModels('openrouter');

    expect(clientGetMock).toHaveBeenCalledWith('/models/discover/openrouter');
    expect(discoveredModels[0].defaultSettings?.provider).toBe('openrouter');
    expect(discoveredModels[0].defaultSettings?.supportsTemperature).toBe(false);
  });
});
