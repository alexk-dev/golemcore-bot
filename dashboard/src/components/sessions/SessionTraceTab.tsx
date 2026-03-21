import { useEffect, useState, type ReactElement } from 'react';
import toast from 'react-hot-toast';

import { useExportSessionTrace, useSessionTrace, useSessionTraceSummary } from '../../hooks/useSessions';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import { SessionTraceExplorer } from './SessionTraceExplorer';

export interface SessionTraceTabProps {
  sessionId: string | null;
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

export function SessionTraceTab({ sessionId }: SessionTraceTabProps): ReactElement {
  const traceEnabled = sessionId != null && sessionId.length > 0;
  const [requestedTraceId, setRequestedTraceId] = useState<string | null>(null);
  const { data: traceSummary, isLoading: traceSummaryLoading, error: traceSummaryError } = useSessionTraceSummary(
    sessionId ?? '',
    traceEnabled,
  );
  const { data: trace, isLoading: traceLoading, error: traceError } = useSessionTrace(
    sessionId ?? '',
    traceEnabled && requestedTraceId != null,
  );
  const exportTraceMut = useExportSessionTrace();

  useEffect(() => {
    // Reset detail loading state when the user switches to another session.
    setRequestedTraceId(null);
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

  return (
    <SessionTraceExplorer
      summary={traceSummary ?? null}
      trace={trace ?? null}
      isLoadingSummary={traceSummaryLoading}
      isLoadingTrace={traceLoading}
      errorMessage={buildTraceErrorMessage(traceSummaryError, traceError)}
      preferredTraceId={requestedTraceId}
      onLoadTrace={setRequestedTraceId}
      onExport={() => { void handleTraceExport(); }}
      isExporting={exportTraceMut.isPending}
    />
  );
}
