import { beforeEach, describe, expect, it, vi } from 'vitest';

const clientPut = vi.hoisted(() => vi.fn(() => Promise.resolve({ data: {} })));
const clientPost = vi.hoisted(() => vi.fn(() => Promise.resolve({ data: {} })));
const clientGet = vi.hoisted(() => vi.fn(() => Promise.resolve({ data: {} })));

vi.mock('./client', () => ({
  default: {
    get: clientGet,
    post: clientPost,
    put: clientPut,
  },
}));

import { updateMemoryConfig, updateTelemetryConfig } from './settings';

describe('settings telemetry metadata', () => {
  beforeEach(() => {
    clientPut.mockClear();
  });

  it('marks memory saves with the memory section telemetry key', async () => {
    await updateMemoryConfig({
      enabled: true,
      softPromptBudgetTokens: 2048,
      maxPromptBudgetTokens: 8192,
      workingTopK: 8,
      episodicTopK: 8,
      semanticTopK: 8,
      proceduralTopK: 4,
      promotionEnabled: true,
      promotionMinConfidence: 0.75,
      decayEnabled: true,
      decayDays: 30,
      retrievalLookbackDays: 14,
      codeAwareExtractionEnabled: true,
      disclosure: null,
      diagnostics: null,
    });

    expect(clientPut).toHaveBeenCalledWith(
      '/settings/runtime/memory',
      expect.any(Object),
      expect.objectContaining({
        _telemetry: {
          counterKey: 'settings_save_count_by_section',
          value: 'memory',
        },
      }),
    );
  });

  it('marks telemetry saves with the telemetry section key', async () => {
    await updateTelemetryConfig({ enabled: true });

    expect(clientPut).toHaveBeenCalledWith(
      '/settings/runtime/telemetry',
      { enabled: true },
      expect.objectContaining({
        _telemetry: {
          counterKey: 'settings_save_count_by_section',
          value: 'telemetry',
        },
      }),
    );
  });
});
