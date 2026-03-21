import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { SessionTraceTab } from '../components/sessions/SessionTraceTab';

const sessionHookMocks = vi.hoisted(() => ({
  useSessions: vi.fn(),
  useSession: vi.fn(),
  useSessionTraceSummary: vi.fn(),
  useSessionTrace: vi.fn(),
  useDeleteSession: vi.fn(),
  useCompactSession: vi.fn(),
  useClearSession: vi.fn(),
  useExportSessionTrace: vi.fn(),
}));

vi.mock('../hooks/useSessions', () => ({
  useSessions: sessionHookMocks.useSessions,
  useSession: sessionHookMocks.useSession,
  useSessionTraceSummary: sessionHookMocks.useSessionTraceSummary,
  useSessionTrace: sessionHookMocks.useSessionTrace,
  useDeleteSession: sessionHookMocks.useDeleteSession,
  useCompactSession: sessionHookMocks.useCompactSession,
  useClearSession: sessionHookMocks.useClearSession,
  useExportSessionTrace: sessionHookMocks.useExportSessionTrace,
}));

vi.mock('react-hot-toast', () => ({
  default: {
    success: vi.fn(),
  },
}));

vi.mock('../components/common/ConfirmModal', () => ({
  default: () => null,
}));

vi.mock('../components/sessions/SessionTraceExplorer', () => ({
  SessionTraceExplorer: () => null,
}));

describe('SessionsPage', () => {
  it('does not enable trace detail loading while modal stays on messages tab', () => {
    sessionHookMocks.useSession.mockReturnValue({ data: null });
    sessionHookMocks.useSessionTraceSummary.mockReturnValue({ data: null, isLoading: false, error: null });
    sessionHookMocks.useSessionTrace.mockReturnValue({ data: null, isLoading: false });
    sessionHookMocks.useExportSessionTrace.mockReturnValue({ isPending: false, mutateAsync: vi.fn() });

    renderToStaticMarkup(
      <SessionTraceTab sessionId={null} />,
    );

    expect(sessionHookMocks.useSessionTraceSummary).toHaveBeenCalledWith('', false);
    expect(sessionHookMocks.useSessionTrace).toHaveBeenCalledWith('', false);
  });

  it('loads trace summary before full trace details when trace tab is active', () => {
    sessionHookMocks.useSession.mockReturnValue({ data: { messages: [] } });
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
    sessionHookMocks.useSessionTrace.mockReturnValue({ data: null, isLoading: false });
    sessionHookMocks.useExportSessionTrace.mockReturnValue({ isPending: false, mutateAsync: vi.fn() });

    renderToStaticMarkup(
      <SessionTraceTab sessionId="session-1" />,
    );

    expect(sessionHookMocks.useSessionTraceSummary).toHaveBeenCalledWith('session-1', true);
    expect(sessionHookMocks.useSessionTrace).toHaveBeenCalledWith('session-1', false);
  });
});
