import { beforeEach, describe, expect, it, vi } from 'vitest';

const clientGetMock = vi.fn();

vi.mock('./client', () => ({
  default: {
    get: clientGetMock,
  },
}));

describe('selfEvolving api', () => {
  beforeEach(() => {
    clientGetMock.mockReset();
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
});
