import { beforeEach, describe, expect, it, vi } from 'vitest';

interface SaveModelRequest {
  id: string;
  previousId: string | null;
  settings: {
    provider: string;
  };
}

interface SaveModelConfig {
  _telemetry: {
    counterKey: string;
    value: string;
  };
}

const clientPost = vi.hoisted(() => vi.fn(() => Promise.resolve({ data: {} })));
const clientGet = vi.hoisted(() => vi.fn(() => Promise.resolve({ data: {} })));
const clientPut = vi.hoisted(() => vi.fn(() => Promise.resolve({ data: {} })));
const clientDelete = vi.hoisted(() => vi.fn(() => Promise.resolve({ data: {} })));

vi.mock('./client', () => ({
  default: {
    delete: clientDelete,
    get: clientGet,
    post: clientPost,
    put: clientPut,
  },
}));

import { saveModel } from './models';

describe('model catalog telemetry metadata', () => {
  beforeEach(() => {
    clientPost.mockClear();
  });

  it('marks model saves with the model catalog section telemetry key', async () => {
    await saveModel('gpt-4.1', {
      provider: 'openai',
      displayName: 'GPT-4.1',
      supportsVision: true,
      supportsTemperature: true,
      maxInputTokens: 128000,
      reasoning: null,
    });

    const call = clientPost.mock.calls[0] as [string, SaveModelRequest, SaveModelConfig] | undefined;
    if (call == null) {
      throw new Error('Expected saveModel to call client.post');
    }

    const [url, body, config] = call;
    expect(url).toBe('/models');
    expect(body.id).toBe('gpt-4.1');
    expect(body.previousId).toBeNull();
    expect(body.settings.provider).toBe('openai');
    expect(config._telemetry).toEqual({
      counterKey: 'settings_save_count_by_section',
      value: 'model-catalog',
    });
  });
});
