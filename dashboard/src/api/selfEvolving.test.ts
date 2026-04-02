import { beforeEach, describe, expect, it, vi } from 'vitest';

const clientGetMock = vi.fn();
const clientPostMock = vi.fn();

vi.mock('./client', () => ({
  default: {
    get: clientGetMock,
    post: clientPostMock,
  },
}));

describe('selfEvolving api', () => {
  beforeEach(() => {
    clientGetMock.mockReset();
    clientPostMock.mockReset();
  });

  it('loads compare evidence from the dedicated compare endpoint', async () => {
    clientGetMock.mockResolvedValue({ data: { payloadKind: 'compare' } });

    const api = await import('./selfEvolving');
    const result = await api.getSelfEvolvingArtifactCompareEvidence('stream-1', 'rev-1', 'rev-2');

    expect(clientGetMock).toHaveBeenCalledWith('/self-evolving/artifacts/stream-1/compare-evidence', {
      params: { fromRevisionId: 'rev-1', toRevisionId: 'rev-2' },
    });
    expect(result).toEqual({ payloadKind: 'compare' });
  });

  it('loads dedicated tactic search status with local embedding runtime fields', async () => {
    clientGetMock.mockResolvedValue({
      data: {
        mode: 'hybrid',
        reason: 'local embedding model unavailable',
        provider: 'ollama',
        model: 'qwen3-embedding:0.6b',
        degraded: true,
        runtimeHealthy: false,
        modelAvailable: false,
        autoInstallConfigured: true,
        pullOnStartConfigured: true,
        pullAttempted: true,
        pullSucceeded: false,
        updatedAt: '2026-04-01T23:30:00Z',
      },
    });

    const api = await import('./selfEvolving');
    const result = await api.getSelfEvolvingTacticSearchStatus();

    expect(clientGetMock).toHaveBeenCalledWith('/self-evolving/tactics/status');
    expect(result.provider).toBe('ollama');
    expect(result.model).toBe('qwen3-embedding:0.6b');
    expect(result.runtimeHealthy).toBe(false);
    expect(result.pullAttempted).toBe(true);
  });

  it('requests explicit local model install for tactic embeddings', async () => {
    clientPostMock.mockResolvedValue({
      data: {
        mode: 'hybrid',
        reason: null,
        provider: 'ollama',
        model: 'qwen3-embedding:0.6b',
        degraded: false,
        runtimeHealthy: true,
        modelAvailable: true,
        autoInstallConfigured: true,
        pullOnStartConfigured: false,
        pullAttempted: true,
        pullSucceeded: true,
        updatedAt: '2026-04-02T01:00:00Z',
      },
    });

    const api = await import('./selfEvolving');
    const result = await api.installSelfEvolvingTacticEmbeddingModel();

    expect(clientPostMock).toHaveBeenCalledWith('/self-evolving/tactics/install');
    expect(result.modelAvailable).toBe(true);
    expect(result.pullSucceeded).toBe(true);
  });
});
