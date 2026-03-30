import { beforeEach, describe, expect, it, vi } from 'vitest';

const clientGetMock = vi.fn();

vi.mock('./client', () => ({
  default: {
    get: clientGetMock,
  },
}));

describe('sessions api', () => {
  beforeEach(() => {
    clientGetMock.mockReset();
  });

  it('exports a full trace snapshot payload from the dedicated endpoint', async () => {
    clientGetMock.mockResolvedValue({ data: '{"full":"payload"}' });

    const sessionsApi = await import('./sessions');
    const exportSnapshotPayload = Reflect.get(sessionsApi, 'exportSessionTraceSnapshotPayload');

    expect(typeof exportSnapshotPayload).toBe('function');

    if (typeof exportSnapshotPayload !== 'function') {
      return;
    }

    const result = await exportSnapshotPayload('session-1', 'snap-1');

    expect(clientGetMock).toHaveBeenCalledWith('/sessions/session-1/trace/snapshots/snap-1/payload', {
      responseType: 'text',
    });
    expect(result).toBe('{"full":"payload"}');
  });
});
