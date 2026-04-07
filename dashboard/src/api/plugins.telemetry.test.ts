import { beforeEach, describe, expect, it, vi } from 'vitest';

const clientPut = vi.hoisted(() => vi.fn(() => Promise.resolve({ data: {} })));
const clientGet = vi.hoisted(() => vi.fn(() => Promise.resolve({ data: {} })));
const clientPost = vi.hoisted(() => vi.fn(() => Promise.resolve({ data: {} })));

vi.mock('./client', () => ({
  default: {
    get: clientGet,
    post: clientPost,
    put: clientPut,
  },
}));

import { savePluginSettingsSection } from './plugins';

describe('plugin settings telemetry metadata', () => {
  beforeEach(() => {
    clientPut.mockClear();
  });

  it('marks plugin settings saves with the route key as telemetry dimension', async () => {
    await savePluginSettingsSection('plugin-browser', { endpoint: 'https://example.com' });

    expect(clientPut).toHaveBeenCalledWith(
      '/plugins/settings/sections/plugin-browser',
      { endpoint: 'https://example.com' },
      expect.objectContaining({
        _telemetry: {
          counterKey: 'settings_save_count_by_section',
          value: 'plugin-browser',
        },
      }),
    );
  });
});
