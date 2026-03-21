import { useEffect, useMemo, useState, type ReactElement } from 'react';
import { Alert, Badge, Button, Card, Col, Row, Spinner } from 'react-bootstrap';

import type {
  SessionTrace,
  SessionTraceRecord,
  SessionTraceSpan,
  SessionTraceSummary,
  SessionTraceSummaryItem,
} from '../../api/sessions';
import {
  formatTraceBytes,
  formatTraceDuration,
  getInitialSpan,
  getTraceStatusVariant,
  resolveTraceSelection,
} from '../../lib/traceFormat';
import { SessionTraceSnapshotViewer } from './SessionTraceSnapshotViewer';
import { SessionTraceTimeline } from './SessionTraceTimeline';
import { SessionTraceTree } from './SessionTraceTree';

interface SessionTraceExplorerProps {
  summary: SessionTraceSummary | null;
  trace: SessionTrace | null;
  isLoadingSummary: boolean;
  isLoadingTrace: boolean;
  errorMessage: string | null;
  preferredTraceId: string | null;
  onLoadTrace: (traceId: string) => void;
  onExport: () => void;
  isExporting?: boolean;
}

interface TraceStateCardProps {
  message: string;
  variant?: 'secondary' | 'danger';
}

interface TraceHeaderCardProps {
  trace: SessionTrace;
  activeTrace: SessionTraceRecord | null;
  isExporting: boolean;
  onExport: () => void;
  onSelectTrace: (traceId: string) => void;
  onSelectInitialSpan: (trace: SessionTraceRecord) => void;
}

interface ActiveTraceOverviewCardProps {
  trace: SessionTraceRecord;
}

interface TraceSummaryCardProps {
  summary: SessionTraceSummary;
  isLoadingTrace: boolean;
  isExporting: boolean;
  onExport: () => void;
  onLoadTrace: (traceId: string) => void;
}

interface TraceExplorerBodyProps {
  summary: SessionTraceSummary | null;
  trace: SessionTrace | null;
  activeTrace: SessionTraceRecord | null;
  activeSpan: SessionTraceSpan | null;
  isLoadingSummary: boolean;
  isLoadingTrace: boolean;
  isExporting: boolean;
  errorMessage: string | null;
  onExport: () => void;
  onLoadTrace: (traceId: string) => void;
  onSelectTrace: (traceId: string) => void;
  onSelectInitialSpan: (trace: SessionTraceRecord) => void;
  onSelectSpan: (spanId: string) => void;
}

interface TraceLoadedContentProps {
  trace: SessionTrace;
  activeTrace: SessionTraceRecord | null;
  activeSpan: SessionTraceSpan | null;
  isExporting: boolean;
  onExport: () => void;
  onSelectTrace: (traceId: string) => void;
  onSelectInitialSpan: (trace: SessionTraceRecord) => void;
  onSelectSpan: (spanId: string) => void;
}

function resolveActiveTrace(trace: SessionTrace | null, traceId: string | null): SessionTraceRecord | null {
  if (trace == null || trace.traces.length === 0) {
    return null;
  }
  return trace.traces.find((item) => item.traceId === traceId) ?? trace.traces[0];
}

function resolveActiveSpan(record: SessionTraceRecord | null, spanId: string | null): SessionTraceSpan | null {
  if (record == null || record.spans.length === 0) {
    return null;
  }
  return record.spans.find((item) => item.spanId === spanId) ?? getInitialSpan(record);
}

function TraceStateCard({ message, variant = 'secondary' }: TraceStateCardProps): ReactElement {
  return (
    <Card className="settings-card">
      <Card.Body>
        <h3 className="h6 mb-2">Trace Explorer</h3>
        <Alert variant={variant} className="mb-0">
          {message}
        </Alert>
      </Card.Body>
    </Card>
  );
}

function TraceLoadingCard(): ReactElement {
  return (
    <Card className="settings-card">
      <Card.Body className="d-flex align-items-center gap-2">
        <Spinner size="sm" animation="border" />
        <span className="small text-body-secondary">Loading trace data...</span>
      </Card.Body>
    </Card>
  );
}

function renderSummaryStatusBadge(item: SessionTraceSummaryItem): ReactElement | null {
  if (item.rootStatusCode == null) {
    return null;
  }
  return (
    <Badge bg={getTraceStatusVariant(item.rootStatusCode)}>
      {item.rootStatusCode}
    </Badge>
  );
}

function TraceHeaderCard({
  trace,
  activeTrace,
  isExporting,
  onExport,
  onSelectTrace,
  onSelectInitialSpan,
}: TraceHeaderCardProps): ReactElement {
  return (
    <Card className="settings-card">
      <Card.Body>
        <div className="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">
          <div>
            <h3 className="h6 mb-1">Trace Explorer</h3>
            <div className="small text-body-secondary">
              {trace.traces.length} trace{trace.traces.length === 1 ? '' : 's'} stored in this session
            </div>
          </div>
          <Button type="button" size="sm" variant="primary" onClick={onExport} disabled={isExporting}>
            {isExporting ? 'Exporting...' : 'Export JSON'}
          </Button>
        </div>

        <div className="d-flex flex-wrap gap-2 mb-3">
          <Badge bg="secondary">{formatTraceBytes(trace.storageStats.compressedSnapshotBytes)} compressed</Badge>
          <Badge bg="secondary">{formatTraceBytes(trace.storageStats.uncompressedSnapshotBytes)} raw</Badge>
          {trace.storageStats.truncatedTraces > 0 && <Badge bg="warning">{trace.storageStats.truncatedTraces} truncated</Badge>}
        </div>

        <div className="d-flex flex-wrap gap-2">
          {trace.traces.map((item) => {
            const rootSpan = item.spans.find((span) => span.spanId === item.rootSpanId) ?? item.spans[0] ?? null;
            return (
              <Button
                key={item.traceId}
                type="button"
                size="sm"
                variant={item.traceId === activeTrace?.traceId ? 'primary' : 'secondary'}
                onClick={() => {
                  onSelectTrace(item.traceId);
                  onSelectInitialSpan(item);
                }}
              >
                {item.traceName ?? item.traceId}
                {rootSpan != null && (
                  <Badge bg={getTraceStatusVariant(rootSpan.statusCode)} className="ms-2">
                    {rootSpan.statusCode ?? 'UNKNOWN'}
                  </Badge>
                )}
              </Button>
            );
          })}
        </div>
      </Card.Body>
    </Card>
  );
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
          {summary.storageStats.truncatedTraces > 0 && <Badge bg="warning">{summary.storageStats.truncatedTraces} truncated</Badge>}
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

function ActiveTraceOverviewCard({ trace }: ActiveTraceOverviewCardProps): ReactElement {
  const durationMs = trace.endedAt != null && trace.startedAt != null
    ? new Date(trace.endedAt).getTime() - new Date(trace.startedAt).getTime()
    : null;

  return (
    <Card className="settings-card">
      <Card.Body>
        <div className="d-flex flex-wrap gap-3 align-items-center">
          <div>
            <div className="small text-body-secondary">Active trace</div>
            <div className="fw-semibold">{trace.traceName ?? trace.traceId}</div>
          </div>
          <div>
            <div className="small text-body-secondary">Duration</div>
            <div>{formatTraceDuration(durationMs)}</div>
          </div>
          <div>
            <div className="small text-body-secondary">Spans</div>
            <div>{trace.spans.length}</div>
          </div>
          <div>
            <div className="small text-body-secondary">Snapshots</div>
            <div>{trace.spans.reduce((total, span) => total + span.snapshots.length, 0)}</div>
          </div>
        </div>
      </Card.Body>
    </Card>
  );
}

function TraceLoadedContent({
  trace,
  activeTrace,
  activeSpan,
  isExporting,
  onExport,
  onSelectTrace,
  onSelectInitialSpan,
  onSelectSpan,
}: TraceLoadedContentProps): ReactElement {
  return (
    <div className="d-flex flex-column gap-3">
      <TraceHeaderCard
        trace={trace}
        activeTrace={activeTrace}
        isExporting={isExporting}
        onExport={onExport}
        onSelectTrace={onSelectTrace}
        onSelectInitialSpan={onSelectInitialSpan}
      />

      {activeTrace != null && (
        <ActiveTraceOverviewCard trace={activeTrace} />
      )}

      <Row className="g-3">
        <Col lg={5}>
          <SessionTraceTree
            spans={activeTrace?.spans ?? []}
            activeSpanId={activeSpan?.spanId ?? null}
            onSelectSpan={onSelectSpan}
          />
        </Col>
        <Col lg={7}>
          <SessionTraceSnapshotViewer span={activeSpan} />
        </Col>
      </Row>

      <SessionTraceTimeline
        spans={activeTrace?.spans ?? []}
        activeSpanId={activeSpan?.spanId ?? null}
        onSelectSpan={onSelectSpan}
      />
    </div>
  );
}

function TraceExplorerBody({
  summary,
  trace,
  activeTrace,
  activeSpan,
  isLoadingSummary,
  isLoadingTrace,
  isExporting,
  errorMessage,
  onExport,
  onLoadTrace,
  onSelectTrace,
  onSelectInitialSpan,
  onSelectSpan,
}: TraceExplorerBodyProps): ReactElement {
  if (isLoadingSummary) {
    return <TraceLoadingCard />;
  }

  if (errorMessage != null) {
    return <TraceStateCard message={errorMessage} variant="danger" />;
  }

  if (trace == null && summary != null && summary.traces.length > 0) {
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

  if (trace == null || trace.traces.length === 0) {
    return <TraceStateCard message="No traces were captured for this session." />;
  }

  return (
    <TraceLoadedContent
      trace={trace}
      activeTrace={activeTrace}
      activeSpan={activeSpan}
      isExporting={isExporting}
      onExport={onExport}
      onSelectTrace={onSelectTrace}
      onSelectInitialSpan={onSelectInitialSpan}
      onSelectSpan={onSelectSpan}
    />
  );
}

export function SessionTraceExplorer({
  summary,
  trace,
  isLoadingSummary,
  isLoadingTrace,
  errorMessage,
  preferredTraceId,
  onLoadTrace,
  onExport,
  isExporting = false,
}: SessionTraceExplorerProps): ReactElement {
  const [activeTraceId, setActiveTraceId] = useState<string | null>(null);
  const [activeSpanId, setActiveSpanId] = useState<string | null>(null);

  const activeTrace = useMemo(() => resolveActiveTrace(trace, activeTraceId), [trace, activeTraceId]);
  const activeSpan = useMemo(() => resolveActiveSpan(activeTrace, activeSpanId), [activeTrace, activeSpanId]);

  useEffect(() => {
    // Preserve the current selection across trace refetches and only reset when the selected nodes disappear.
    const nextSelection = resolveTraceSelection(trace?.traces, activeTraceId, activeSpanId, preferredTraceId);
    if (nextSelection.traceId !== activeTraceId) {
      setActiveTraceId(nextSelection.traceId);
    }
    if (nextSelection.spanId !== activeSpanId) {
      setActiveSpanId(nextSelection.spanId);
    }
  }, [trace, activeTraceId, activeSpanId, preferredTraceId]);

  return (
    <TraceExplorerBody
      summary={summary}
      trace={trace}
      activeTrace={activeTrace}
      activeSpan={activeSpan}
      isLoadingSummary={isLoadingSummary}
      isLoadingTrace={isLoadingTrace}
      isExporting={isExporting}
      errorMessage={errorMessage}
      onExport={onExport}
      onLoadTrace={onLoadTrace}
      onSelectTrace={setActiveTraceId}
      onSelectInitialSpan={(record) => setActiveSpanId(getInitialSpan(record)?.spanId ?? null)}
      onSelectSpan={setActiveSpanId}
    />
  );
}
