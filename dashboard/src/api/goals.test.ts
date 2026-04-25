import { beforeEach, describe, expect, it, vi } from 'vitest';

const clientGetMock = vi.fn();

vi.mock('./client', () => ({
  default: {
    get: clientGetMock,
  },
}));

describe('goals api', () => {
  beforeEach(() => {
    clientGetMock.mockReset();
  });

  it('requests goals for the active chat session when session params are provided', async () => {
    clientGetMock.mockResolvedValue({
      data: { featureEnabled: true, autoModeEnabled: false, goals: [], standaloneTasks: [] },
    });

    const api = await import('./goals');
    await api.getGoals({ channel: 'web', conversationKey: 'session-123' });

    expect(clientGetMock).toHaveBeenCalledWith('/goals', {
      params: { channel: 'web', conversationKey: 'session-123' },
    });
  });

  it('keeps the legacy unscoped request for callers without session params', async () => {
    clientGetMock.mockResolvedValue({
      data: { featureEnabled: true, autoModeEnabled: false, goals: [], standaloneTasks: [] },
    });

    const api = await import('./goals');
    await api.getGoals();

    expect(clientGetMock).toHaveBeenCalledWith('/goals');
  });
});
