import { useState, type ReactElement } from 'react';
import { Alert, Badge, Button, ButtonGroup, Card, Spinner } from '../ui/tailwind-components';

import type { MessageInfo, SessionTrace, SessionTraceSummary, SessionTraceSummaryItem } from '../../api/sessions';
import { formatTraceBytes, formatTraceDuration, formatTraceTimestamp, getTraceStatusVariant } from '../../lib/traceFormat';
import { SessionTraceFeed } from './SessionTraceFeed';
import { SessionTraceTimeline } from './SessionTraceTimeline';
import { SessionTraceWaterfall } from './SessionTraceWaterfall';

export interface SessionTraceExplorerProps {
  summary: SessionTraceSummary | null;
  trace: SessionTrace | null;
  messages: MessageInfo[];
  isLoadingSummary: boolean;
  isLoadingTrace: boolean;
  errorMessage: string | null;
  onLoadTrace: (traceId: string) => void;
  onExport: () => void;
  onExportSnapshotPayload: (snapshotId: string, role: string | null, spanName: string | null) => Promise<void>;
  isExporting?: boolean;
  isExportingSnapshot?: boolean;
}

type TraceViewMode = 'waterfall' | 'feed' | 'timeline';

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
                  {item.startedAt != null && <span>{formatTraceTimestamp(item.startedAt)}</span>}
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

interface TraceViewTabsProps {
  activeMode: TraceViewMode;
  onChangeMode: (mode: TraceViewMode) => void;
}

function TraceViewTabs({ activeMode, onChangeMode }: TraceViewTabsProps): ReactElement {
  const modes: Array<{ key: TraceViewMode; label: string }> = [
    { key: 'waterfall', label: 'Waterfall' },
    { key: 'feed', label: 'Feed' },
    { key: 'timeline', label: 'Timeline' },
  ];

  return (
    <ButtonGroup size="sm">
      {modes.map((mode) => (
        <Button
          key={mode.key}
          type="button"
          variant={activeMode === mode.key ? 'primary' : 'secondary'}
          onClick={() => onChangeMode(mode.key)}
        >
          {mode.label}
        </Button>
      ))}
    </ButtonGroup>
  );
}

interface TraceContentProps {
  viewMode: TraceViewMode;
  trace: SessionTrace;
  messages: MessageInfo[];
  activeSpanId: string | null;
  onExportSnapshotPayload: (snapshotId: string, role: string | null, spanName: string | null) => Promise<void>;
  isExportingSnapshot: boolean;
  onSelectSpan: (spanId: string) => void;
}

interface WaterfallAccordionProps {
  trace: SessionTrace;
  onExportSnapshotPayload: (snapshotId: string, role: string | null, spanName: string | null) => Promise<void>;
  isExportingSnapshot: boolean;
}

function WaterfallAccordion({
  trace,
  onExportSnapshotPayload,
  isExportingSnapshot,
}: WaterfallAccordionProps): ReactElement {
  const [expandedTraceId, setExpandedTraceId] = useState<string | null>(
    () => trace.traces.length > 0 ? trace.traces[0].traceId : null,
  );

  const handleToggle = (traceId: string): void => {
    setExpandedTraceId((prev) => (prev === traceId ? null : traceId));
  };

  return (
    <div className="d-flex flex-column gap-3">
      {trace.traces.map((record) => (
        <Card key={record.traceId} className="settings-card">
          <Card.Body>
            <SessionTraceWaterfall
              record={record}
              isExpanded={expandedTraceId === record.traceId}
              onExportSnapshotPayload={onExportSnapshotPayload}
              isExportingSnapshot={isExportingSnapshot}
              onToggleExpand={() => handleToggle(record.traceId)}
            />
          </Card.Body>
        </Card>
      ))}
    </div>
  );
}

function TraceContent({
  viewMode,
  trace,
  messages,
  activeSpanId,
  onExportSnapshotPayload,
  isExportingSnapshot,
  onSelectSpan,
}: TraceContentProps): ReactElement {
  if (viewMode === 'feed') {
    return <SessionTraceFeed messages={messages} trace={trace} />;
  }

  if (viewMode === 'timeline') {
    const allSpans = trace.traces.flatMap((record) => record.spans);
    return <SessionTraceTimeline spans={allSpans} activeSpanId={activeSpanId} onSelectSpan={onSelectSpan} />;
  }

  return (
    <WaterfallAccordion
      trace={trace}
      onExportSnapshotPayload={onExportSnapshotPayload}
      isExportingSnapshot={isExportingSnapshot}
    />
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
  onExportSnapshotPayload,
  isExporting = false,
  isExportingSnapshot = false,
}: SessionTraceExplorerProps): ReactElement {
  const [viewMode, setViewMode] = useState<TraceViewMode>('waterfall');
  const [activeSpanId, setActiveSpanId] = useState<string | null>(null);

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
            <div className="d-flex flex-wrap gap-2">
              <TraceViewTabs activeMode={viewMode} onChangeMode={setViewMode} />
              <Button type="button" size="sm" variant="primary" onClick={onExport} disabled={isExporting}>
                {isExporting ? 'Exporting...' : 'Export JSON'}
              </Button>
            </div>
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
      <TraceContent
        viewMode={viewMode}
        trace={trace}
        messages={messages}
        activeSpanId={activeSpanId}
        onExportSnapshotPayload={onExportSnapshotPayload}
        isExportingSnapshot={isExportingSnapshot}
        onSelectSpan={setActiveSpanId}
      />
    </div>
  );
}
