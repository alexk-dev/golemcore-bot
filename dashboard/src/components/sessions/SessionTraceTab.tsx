import { useEffect, useState, type ReactElement } from 'react';
import toast from 'react-hot-toast';

import type { MessageInfo } from '../../api/sessions';
import { useExportSessionTrace, useExportSessionTraceSnapshot, useSessionTrace, useSessionTraceSummary } from '../../hooks/useSessions';
import { exportPayloadAsJson } from '../../lib/tracePayloadExport';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import { SessionTraceExplorer } from './SessionTraceExplorer';

export interface SessionTraceTabProps {
  sessionId: string | null;
  messages: MessageInfo[];
}

function downloadTraceExport(payload: unknown, sessionId: string): void {
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = `session-trace-${sessionId}.json`;
  link.click();
  URL.revokeObjectURL(objectUrl);
}

function buildTraceErrorMessage(summaryError: unknown, traceError: unknown): string | null {
  if (summaryError != null) {
    return `Failed to load trace summary: ${extractErrorMessage(summaryError)}`;
  }
  if (traceError != null) {
    return `Failed to load trace details: ${extractErrorMessage(traceError)}`;
  }
  return null;
}

export function SessionTraceTab({ sessionId, messages }: SessionTraceTabProps): ReactElement {
  const traceEnabled = sessionId != null && sessionId.length > 0;
  const [detailsRequested, setDetailsRequested] = useState(false);
  const { data: traceSummary, isLoading: traceSummaryLoading, error: traceSummaryError } = useSessionTraceSummary(
    sessionId ?? '',
    traceEnabled,
  );
  const { data: trace, isLoading: traceLoading, error: traceError } = useSessionTrace(
    sessionId ?? '',
    traceEnabled && detailsRequested,
  );
  const exportTraceMut = useExportSessionTrace();
  const exportSnapshotPayloadMut = useExportSessionTraceSnapshot();

  useEffect(() => {
    // Reset detail loading state when the user switches to another session.
    setDetailsRequested(false);
  }, [sessionId]);

  const handleTraceExport = async (): Promise<void> => {
    if (sessionId == null || sessionId.length === 0) {
      return;
    }
    try {
      const payload = await exportTraceMut.mutateAsync(sessionId);
      downloadTraceExport(payload, sessionId);
      toast.success('Trace export downloaded');
    } catch (error) {
      toast.error(`Failed to export trace: ${extractErrorMessage(error)}`);
    }
  };

  const handleSnapshotPayloadExport = async (
    snapshotId: string,
    role: string | null,
    spanName: string | null,
  ): Promise<void> => {
    if (sessionId == null || sessionId.length === 0) {
      return;
    }
    try {
      const payload = await exportSnapshotPayloadMut.mutateAsync({ sessionId, snapshotId });
      exportPayloadAsJson(payload, role, spanName);
      toast.success('Payload exported');
    } catch (error) {
      toast.error(`Failed to export payload: ${extractErrorMessage(error)}`);
    }
  };

  return (
      <SessionTraceExplorer
        summary={traceSummary ?? null}
        trace={trace ?? null}
        messages={messages}
        isLoadingSummary={traceSummaryLoading}
        isLoadingTrace={traceLoading}
        errorMessage={buildTraceErrorMessage(traceSummaryError, traceError)}
        onLoadTrace={() => setDetailsRequested(true)}
        onExport={() => { void handleTraceExport(); }}
        onExportSnapshotPayload={handleSnapshotPayloadExport}
        isExporting={exportTraceMut.isPending}
        isExportingSnapshot={exportSnapshotPayloadMut.isPending}
      />
  );
}
