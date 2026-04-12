import { beforeEach, describe, expect, it, vi } from 'vitest';

const clientPostMock = vi.fn();

vi.mock('./client', () => ({
  default: {
    post: clientPostMock,
  },
}));

describe('settings llm provider api', () => {
  beforeEach(() => {
    clientPostMock.mockReset();
  });

  it('posts add-and-import requests to the provider import endpoint', async () => {
    clientPostMock.mockResolvedValue({
      data: {
        providerSaved: true,
        providerName: 'xmesh',
        resolvedEndpoint: 'https://models.example.com/v1/models',
        addedModels: ['xmesh/gpt-5.2'],
        skippedModels: ['xmesh/existing'],
        errors: [],
      },
    });

    const api = await import('./settings');
    const result = await api.addLlmProviderAndImport('xmesh', {
      apiKey: 'secret-token',
      apiKeyPresent: true,
      baseUrl: 'https://models.example.com/v1',
      requestTimeoutSeconds: 30,
      apiType: 'openai',
      legacyApi: null,
    }, ['xmesh/gpt-5.2']);

    expect(clientPostMock).toHaveBeenCalledWith('/settings/runtime/llm/providers/xmesh/import-models', {
      config: {
        baseUrl: 'https://models.example.com/v1',
        requestTimeoutSeconds: 30,
        apiKey: { value: 'secret-token', encrypted: false },
        apiType: 'openai',
        legacyApi: null,
      },
      selectedModelIds: ['xmesh/gpt-5.2'],
    }, expect.objectContaining({
      _telemetry: {
        counterKey: 'settings_save_count_by_section',
        value: 'llm-providers',
      },
    }));
    expect(result.addedModels).toEqual(['xmesh/gpt-5.2']);
    expect(result.skippedModels).toEqual(['xmesh/existing']);
  });

  it('posts saved provider test requests with saved mode', async () => {
    clientPostMock.mockResolvedValue({
      data: {
        mode: 'saved',
        providerName: 'openrouter',
        resolvedEndpoint: 'https://openrouter.ai/api/v1/models',
        models: ['openrouter/openai/gpt-5'],
        success: true,
        error: null,
      },
    });

    const api = await import('./settings');
    const result = await api.testSavedLlmProvider('openrouter');

    expect(clientPostMock).toHaveBeenCalledWith('/settings/runtime/llm/provider-tests', {
      mode: 'saved',
      providerName: 'openrouter',
      config: null,
    });
    expect(result.success).toBe(true);
    expect(result.models).toEqual(['openrouter/openai/gpt-5']);
  });

  it('posts draft provider test requests with transient config payload', async () => {
    clientPostMock.mockResolvedValue({
      data: {
        mode: 'draft',
        providerName: 'draftmesh',
        resolvedEndpoint: 'https://draft.example.com/v1/models',
        models: ['draftmesh/draft-gpt'],
        success: true,
        error: null,
      },
    });

    const api = await import('./settings');
    const result = await api.testDraftLlmProvider('draftmesh', {
      apiKey: 'draft-token',
      apiKeyPresent: false,
      baseUrl: 'https://draft.example.com',
      requestTimeoutSeconds: 25,
      apiType: 'openai',
      legacyApi: null,
    });

    expect(clientPostMock).toHaveBeenCalledWith('/settings/runtime/llm/provider-tests', {
      mode: 'draft',
      providerName: 'draftmesh',
      config: {
        baseUrl: 'https://draft.example.com',
        requestTimeoutSeconds: 25,
        apiKey: { value: 'draft-token', encrypted: false },
        apiType: 'openai',
        legacyApi: null,
      },
    });
    expect(result.mode).toBe('draft');
    expect(result.models).toEqual(['draftmesh/draft-gpt']);
  });
});
