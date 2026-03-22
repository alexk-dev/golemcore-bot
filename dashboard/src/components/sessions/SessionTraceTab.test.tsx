import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { SessionTraceTab } from './SessionTraceTab';

const sessionHookMocks = vi.hoisted(() => ({
  useSessionTraceSummary: vi.fn(),
  useSessionTrace: vi.fn(),
  useExportSessionTrace: vi.fn(),
}));

vi.mock('../../hooks/useSessions', () => ({
  useSessionTraceSummary: sessionHookMocks.useSessionTraceSummary,
  useSessionTrace: sessionHookMocks.useSessionTrace,
  useExportSessionTrace: sessionHookMocks.useExportSessionTrace,
}));

vi.mock('react-hot-toast', () => ({
  default: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

vi.mock('./SessionTraceExplorer', () => ({
  SessionTraceExplorer: () => null,
}));

describe('SessionTraceTab', () => {
  it('does not enable trace detail loading while session id is absent', () => {
    sessionHookMocks.useSessionTraceSummary.mockReturnValue({ data: null, isLoading: false, error: null });
    sessionHookMocks.useSessionTrace.mockReturnValue({ data: null, isLoading: false, error: null });
    sessionHookMocks.useExportSessionTrace.mockReturnValue({ isPending: false, mutateAsync: vi.fn() });

    renderToStaticMarkup(<SessionTraceTab sessionId={null} messages={[]} />);

    expect(sessionHookMocks.useSessionTraceSummary).toHaveBeenCalledWith('', false);
    expect(sessionHookMocks.useSessionTrace).toHaveBeenCalledWith('', false);
  });

  it('loads trace summary before full trace details when trace tab is active', () => {
    sessionHookMocks.useSessionTraceSummary.mockReturnValue({
      data: {
        sessionId: 'session-1',
        traceCount: 1,
        spanCount: 2,
        snapshotCount: 0,
        storageStats: {
          compressedSnapshotBytes: 0,
          uncompressedSnapshotBytes: 0,
          evictedSnapshots: 0,
          evictedTraces: 0,
          truncatedTraces: 0,
        },
        traces: [],
      },
      isLoading: false,
      error: null,
    });
    sessionHookMocks.useSessionTrace.mockReturnValue({ data: null, isLoading: false, error: null });
    sessionHookMocks.useExportSessionTrace.mockReturnValue({ isPending: false, mutateAsync: vi.fn() });

    renderToStaticMarkup(<SessionTraceTab sessionId="session-1" messages={[]} />);

    expect(sessionHookMocks.useSessionTraceSummary).toHaveBeenCalledWith('session-1', true);
    expect(sessionHookMocks.useSessionTrace).toHaveBeenCalledWith('session-1', false);
  });
});
