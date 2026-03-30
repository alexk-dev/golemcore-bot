import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { SessionTraceTab } from './SessionTraceTab';

const sessionHookMocks = vi.hoisted(() => ({
  useSessionTraceSummary: vi.fn(),
  useSessionTrace: vi.fn(),
  useExportSessionTrace: vi.fn(),
  useExportSessionTraceSnapshot: vi.fn(),
}));

const exportPayloadAsJsonMock = vi.hoisted(() => vi.fn());
const explorerPropsRef = vi.hoisted(() => ({
  current: null as Record<string, unknown> | null,
}));

vi.mock('../../hooks/useSessions', () => ({
  useSessionTraceSummary: sessionHookMocks.useSessionTraceSummary,
  useSessionTrace: sessionHookMocks.useSessionTrace,
  useExportSessionTrace: sessionHookMocks.useExportSessionTrace,
  useExportSessionTraceSnapshot: sessionHookMocks.useExportSessionTraceSnapshot,
}));

vi.mock('../../lib/tracePayloadExport', () => ({
  exportPayloadAsJson: exportPayloadAsJsonMock,
}));

vi.mock('react-hot-toast', () => ({
  default: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

vi.mock('./SessionTraceExplorer', () => ({
  SessionTraceExplorer: (props: Record<string, unknown>) => {
    explorerPropsRef.current = props;
    return null;
  },
}));

describe('SessionTraceTab', () => {
  it('exports a full snapshot payload instead of the truncated preview', async () => {
    const exportSnapshotPayloadMock = vi.fn().mockResolvedValue('{"full":"payload"}');
    sessionHookMocks.useSessionTraceSummary.mockReturnValue({ data: null, isLoading: false, error: null });
    sessionHookMocks.useSessionTrace.mockReturnValue({ data: null, isLoading: false, error: null });
    sessionHookMocks.useExportSessionTrace.mockReturnValue({ isPending: false, mutateAsync: vi.fn() });
    sessionHookMocks.useExportSessionTraceSnapshot.mockReturnValue({
      isPending: false,
      mutateAsync: exportSnapshotPayloadMock,
    });

    renderToStaticMarkup(<SessionTraceTab sessionId="session-1" messages={[]} />);

    const onExportSnapshotPayload = explorerPropsRef.current == null
      ? undefined
      : Reflect.get(explorerPropsRef.current, 'onExportSnapshotPayload');

    expect(typeof onExportSnapshotPayload).toBe('function');

    if (typeof onExportSnapshotPayload !== 'function') {
      return;
    }

    await onExportSnapshotPayload('snap-1', 'response', 'response.route');

    expect(exportSnapshotPayloadMock).toHaveBeenCalledWith({ sessionId: 'session-1', snapshotId: 'snap-1' });
    expect(exportPayloadAsJsonMock).toHaveBeenCalledWith('{"full":"payload"}', 'response', 'response.route');
  });

  it('does not enable trace detail loading while session id is absent', () => {
    sessionHookMocks.useSessionTraceSummary.mockReturnValue({ data: null, isLoading: false, error: null });
    sessionHookMocks.useSessionTrace.mockReturnValue({ data: null, isLoading: false, error: null });
    sessionHookMocks.useExportSessionTrace.mockReturnValue({ isPending: false, mutateAsync: vi.fn() });
    sessionHookMocks.useExportSessionTraceSnapshot.mockReturnValue({ isPending: false, mutateAsync: vi.fn() });

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
    sessionHookMocks.useExportSessionTraceSnapshot.mockReturnValue({ isPending: false, mutateAsync: vi.fn() });

    renderToStaticMarkup(<SessionTraceTab sessionId="session-1" messages={[]} />);

    expect(sessionHookMocks.useSessionTraceSummary).toHaveBeenCalledWith('session-1', true);
    expect(sessionHookMocks.useSessionTrace).toHaveBeenCalledWith('session-1', false);
  });
});
