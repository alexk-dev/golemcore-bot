import { beforeEach, describe, expect, it, vi } from 'vitest';

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

    expect(clientPost).toHaveBeenCalledWith(
      '/models/gpt-4.1',
      expect.objectContaining({
        provider: 'openai',
      }),
      expect.objectContaining({
        _telemetry: {
          counterKey: 'settings_save_count_by_section',
          value: 'model-catalog',
        },
      }),
    );
  });
});
