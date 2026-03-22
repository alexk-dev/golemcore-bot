import type { ReactElement } from 'react';
import { Alert, Badge, Button, Card, Spinner } from 'react-bootstrap';

import type { MessageInfo, SessionTrace, SessionTraceSummary, SessionTraceSummaryItem } from '../../api/sessions';
import { formatTraceBytes, formatTraceDuration, getTraceStatusVariant } from '../../lib/traceFormat';
import { SessionTraceFeed } from './SessionTraceFeed';

export interface SessionTraceExplorerProps {
  summary: SessionTraceSummary | null;
  trace: SessionTrace | null;
  messages: MessageInfo[];
  isLoadingSummary: boolean;
  isLoadingTrace: boolean;
  errorMessage: string | null;
  onLoadTrace: (traceId: string) => void;
  onExport: () => void;
  isExporting?: boolean;
}

interface TraceSummaryCardProps {
  summary: SessionTraceSummary;
  isLoadingTrace: boolean;
  isExporting: boolean;
  onExport: () => void;
  onLoadTrace: (traceId: string) => void;
}

function renderSummaryStatusBadge(item: SessionTraceSummaryItem): ReactElement | null {
  if (item.rootStatusCode == null) {
    return null;
  }
  return <Badge bg={getTraceStatusVariant(item.rootStatusCode)}>{item.rootStatusCode}</Badge>;
}

function TraceSummaryCard({
  summary,
  isLoadingTrace,
  isExporting,
  onExport,
  onLoadTrace,
}: TraceSummaryCardProps): ReactElement {
  return (
    <Card className="settings-card">
      <Card.Body>
        <div className="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">
          <div>
            <h3 className="h6 mb-1">Trace summary</h3>
            <div className="small text-body-secondary">
              {summary.traceCount} trace{summary.traceCount === 1 ? '' : 's'} ready to inspect
            </div>
          </div>
          <Button type="button" size="sm" variant="primary" onClick={onExport} disabled={isExporting}>
            {isExporting ? 'Exporting...' : 'Export JSON'}
          </Button>
        </div>
        <div className="d-flex flex-wrap gap-2 mb-3">
          <Badge bg="secondary">{formatTraceBytes(summary.storageStats.compressedSnapshotBytes)} compressed</Badge>
          <Badge bg="secondary">{formatTraceBytes(summary.storageStats.uncompressedSnapshotBytes)} raw</Badge>
          {summary.storageStats.truncatedTraces > 0 && (
            <Badge bg="warning">{summary.storageStats.truncatedTraces} truncated</Badge>
          )}
        </div>
        <div className="d-flex flex-column gap-2">
          {summary.traces.map((item) => (
            <div
              key={item.traceId}
              className="d-flex flex-wrap justify-content-between align-items-center gap-2 rounded border border-border/70 bg-card/60 px-3 py-2"
            >
              <div className="d-flex flex-column gap-1">
                <div className="fw-semibold">{item.traceName ?? item.traceId}</div>
                <div className="small text-body-secondary d-flex flex-wrap align-items-center gap-2">
                  <span>{item.spanCount} spans</span>
                  <span>{item.snapshotCount} snapshots</span>
                  <span>{formatTraceDuration(item.durationMs)}</span>
                  {renderSummaryStatusBadge(item)}
                </div>
              </div>
              <Button
                type="button"
                size="sm"
                variant="secondary"
                onClick={() => onLoadTrace(item.traceId)}
                disabled={isLoadingTrace}
              >
                {isLoadingTrace ? 'Loading...' : 'Load details'}
              </Button>
            </div>
          ))}
        </div>
      </Card.Body>
    </Card>
  );
}

export function SessionTraceExplorer({
  summary,
  trace,
  messages,
  isLoadingSummary,
  isLoadingTrace,
  errorMessage,
  onLoadTrace,
  onExport,
  isExporting = false,
}: SessionTraceExplorerProps): ReactElement {
  if (errorMessage != null) {
    return (
      <Card className="settings-card">
        <Card.Body>
          <Alert variant="danger" className="mb-0">
            {errorMessage}
          </Alert>
        </Card.Body>
      </Card>
    );
  }

  if (isLoadingSummary) {
    return (
      <Card className="settings-card">
        <Card.Body className="d-flex align-items-center gap-2">
          <Spinner size="sm" animation="border" />
          <span className="small text-body-secondary">Loading trace summary...</span>
        </Card.Body>
      </Card>
    );
  }

  if (summary == null) {
    return (
      <Card className="settings-card">
        <Card.Body>
          <Alert variant="secondary" className="mb-0">
            No traces captured for this session.
          </Alert>
        </Card.Body>
      </Card>
    );
  }

  if (trace == null) {
    return (
      <TraceSummaryCard
        summary={summary}
        isLoadingTrace={isLoadingTrace}
        isExporting={isExporting}
        onExport={onExport}
        onLoadTrace={onLoadTrace}
      />
    );
  }

  return (
    <div className="d-flex flex-column gap-3">
      <Card className="settings-card">
        <Card.Body>
          <div className="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">
            <div>
              <h3 className="h6 mb-1">Conversation + trace</h3>
              <div className="small text-body-secondary">
                {trace.traces.length} trace{trace.traces.length === 1 ? '' : 's'} mapped into a turn-based feed
              </div>
            </div>
            <Button type="button" size="sm" variant="primary" onClick={onExport} disabled={isExporting}>
              {isExporting ? 'Exporting...' : 'Export JSON'}
            </Button>
          </div>
          <div className="d-flex flex-wrap gap-2">
            <Badge bg="secondary">{formatTraceBytes(trace.storageStats.compressedSnapshotBytes)} compressed</Badge>
            <Badge bg="secondary">{formatTraceBytes(trace.storageStats.uncompressedSnapshotBytes)} raw</Badge>
            <Badge bg="secondary">{messages.length} messages</Badge>
            {trace.storageStats.truncatedTraces > 0 && (
              <Badge bg="warning">{trace.storageStats.truncatedTraces} truncated</Badge>
            )}
          </div>
        </Card.Body>
      </Card>
      <SessionTraceFeed messages={messages} trace={trace} />
    </div>
  );
}
