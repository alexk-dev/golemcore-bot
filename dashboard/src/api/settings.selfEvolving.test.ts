import type { SelfEvolvingConfig } from './settings';
import { buildRemoteSelfEvolvingConfig, buildRuntimeConfigFixture } from './settingsSelfEvolvingFixtures.testUtils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const clientGetMock = vi.fn();
const clientPutMock = vi.fn();

vi.mock('./client', () => ({
  default: {
    get: clientGetMock,
    put: clientPutMock,
  },
}));

interface RuntimeConfigPutPayload {
  selfEvolving: SelfEvolvingConfig;
}

function isRuntimeConfigPutPayload(value: unknown): value is RuntimeConfigPutPayload {
  if (value == null || typeof value !== 'object') {
    return false;
  }
  return 'selfEvolving' in value;
}

describe('settings selfEvolving normalization', () => {
  beforeEach(() => {
    clientGetMock.mockReset();
    clientPutMock.mockReset();
  });

  it('normalizes missing and legacy selfevolving judge tiers to supported model tiers', async () => {
    clientGetMock.mockResolvedValue({
      data: buildRuntimeConfigFixture({
        enabled: false,
        judge: {
          primaryTier: 'standard',
          tiebreakerTier: 'premium',
          evolutionTier: 'premium',
        },
        tactics: {
          search: {
            embeddings: {
              enabled: true,
              provider: 'ollama',
              baseUrl: 'http://localhost:11434',
              model: 'qwen3-embedding:0.6b',
            },
          },
        },
      }),
    });

    const api = await import('./settings');
    const result = await api.getRuntimeConfig();

    expect(result.selfEvolving.judge.primaryTier).toBe('smart');
    expect(result.selfEvolving.judge.tiebreakerTier).toBe('deep');
    expect(result.selfEvolving.judge.evolutionTier).toBe('deep');
    expect(result.selfEvolving.managedByProperties).toBe(false);
    expect(result.selfEvolving.overriddenPaths).toEqual([]);
    expect(result.selfEvolving.tactics.search.mode).toBe('hybrid');
    expect(result.selfEvolving.tactics.search.embeddings.provider).toBe('ollama');
    expect(result.selfEvolving.tactics.search.embeddings.model).toBe('qwen3-embedding:0.6b');
    expect(result.selfEvolving.tactics.search.embeddings.local.requireHealthyRuntime).toBe(true);
  });

  it('round-trips selfevolving tactic embeddings through runtime config updates', async () => {
    clientPutMock.mockResolvedValue({
      data: buildRuntimeConfigFixture(buildRemoteSelfEvolvingConfig(true)),
    });

    const api = await import('./settings');
    const result = await api.updateRuntimeConfig(buildRuntimeConfigFixture(buildRemoteSelfEvolvingConfig(false)) as never);

    expect(clientPutMock).toHaveBeenCalled();
    const payload = clientPutMock.mock.calls[0]?.[1] as unknown;
    expect(isRuntimeConfigPutPayload(payload)).toBe(true);
    if (!isRuntimeConfigPutPayload(payload)) {
      throw new Error('Expected updateRuntimeConfig to send a selfEvolving payload');
    }
    expect(payload.selfEvolving.tactics.search.mode).toBe('hybrid');
    expect(payload.selfEvolving.tactics.search.embeddings.provider).toBe('openai_compatible');
    expect(payload.selfEvolving.tactics.search.embeddings.model).toBe('text-embedding-3-large');
    expect(payload.selfEvolving.tactics.search.embeddings.local.autoInstall).toBe(false);
    expect(payload.selfEvolving.tactics.search.embeddings.local.requireHealthyRuntime).toBe(true);
    expect('managedByProperties' in payload.selfEvolving).toBe(false);
    expect('overriddenPaths' in payload.selfEvolving).toBe(false);
    expect(result.selfEvolving.managedByProperties).toBe(true);
    expect(result.selfEvolving.overriddenPaths).toEqual(['enabled', 'tactics.search.mode']);
    expect(result.selfEvolving.tactics.search.embeddings.baseUrl).toBe('https://api.example.com/v1');
  });

  it('materializes local ollama provider before saving hybrid tactic embeddings', async () => {
    clientPutMock.mockResolvedValue({
      data: buildRuntimeConfigFixture({
        enabled: true,
        tactics: {
          enabled: true,
          search: {
            mode: 'hybrid',
            embeddings: {
              enabled: true,
              provider: 'ollama',
              baseUrl: null,
              apiKey: null,
              model: 'bge-m3',
              dimensions: 1024,
              batchSize: 16,
              timeoutMs: null,
              autoFallbackToBm25: true,
              local: {
                autoInstall: false,
                pullOnStart: false,
                requireHealthyRuntime: true,
                failOpen: true,
              },
            },
          },
        },
      }),
    });

    const api = await import('./settings');
    await api.updateRuntimeConfig(buildRuntimeConfigFixture({
      enabled: true,
      tactics: {
        enabled: true,
        search: {
          mode: 'hybrid',
          embeddings: {
            enabled: true,
            provider: null,
            baseUrl: null,
            apiKey: null,
            model: 'bge-m3',
            dimensions: 1024,
            batchSize: 16,
            timeoutMs: null,
            autoFallbackToBm25: true,
            local: {
              autoInstall: false,
              pullOnStart: false,
              requireHealthyRuntime: true,
              failOpen: true,
            },
          },
        },
      },
    }) as never);

    expect(clientPutMock).toHaveBeenCalled();
    const payload = clientPutMock.mock.calls[0]?.[1] as unknown;
    expect(isRuntimeConfigPutPayload(payload)).toBe(true);
    if (!isRuntimeConfigPutPayload(payload)) {
      throw new Error('Expected updateRuntimeConfig to send a selfEvolving payload');
    }
    expect(payload.selfEvolving.tactics.search.embeddings.provider).toBe('ollama');
    expect(payload.selfEvolving.tactics.search.embeddings.model).toBe('bge-m3');
  });
});
