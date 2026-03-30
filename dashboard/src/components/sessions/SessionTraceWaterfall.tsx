import { useMemo, useState, type ReactElement } from 'react';
import { Badge } from 'react-bootstrap';

import type { SessionTraceRecord, SessionTraceSpan } from '../../api/sessions';
import {
  buildTraceTree,
  flattenTraceTree,
  formatTraceDuration,
  formatTraceTime,
  getTraceStatusVariant,
} from '../../lib/traceFormat';
import { SessionTraceSpanDetail } from './SessionTraceSpanDetail';

export interface SessionTraceWaterfallProps {
  record: SessionTraceRecord;
  isExpanded: boolean;
  onExportSnapshotPayload: (snapshotId: string, role: string | null, spanName: string | null) => Promise<void>;
  isExportingSnapshot: boolean;
  onToggleExpand: () => void;
}

interface WaterfallRow {
  span: SessionTraceSpan;
  depth: number;
  offsetPct: number;
  widthPct: number;
}

function computeWaterfallRows(record: SessionTraceRecord): WaterfallRow[] {
  const flat = flattenTraceTree(buildTraceTree(record.spans));
  if (flat.length === 0) {
    return [];
  }

  const traceStart = parseTime(record.startedAt) ?? parseTime(flat[0].span.startedAt) ?? 0;
  const traceEnd = parseTime(record.endedAt) ?? computeTraceEnd(flat, traceStart);
  const traceDuration = Math.max(traceEnd - traceStart, 1);

  return flat.map(({ span, depth }) => {
    const spanStart = parseTime(span.startedAt) ?? traceStart;
    const spanEnd = parseTime(span.endedAt) ?? (span.durationMs != null ? spanStart + span.durationMs : spanStart + 1);
    const offsetPct = ((spanStart - traceStart) / traceDuration) * 100;
    const widthPct = Math.max(((spanEnd - spanStart) / traceDuration) * 100, 0.5);
    return { span, depth, offsetPct, widthPct };
  });
}

function parseTime(value: string | null): number | null {
  if (value == null || value.length === 0) {
    return null;
  }
  const ms = Date.parse(value);
  return Number.isNaN(ms) ? null : ms;
}

function computeTraceEnd(flat: Array<{ span: SessionTraceSpan; depth: number }>, fallback: number): number {
  let max = fallback;
  for (const { span } of flat) {
    const end = parseTime(span.endedAt);
    if (end != null && end > max) {
      max = end;
    }
    const start = parseTime(span.startedAt);
    if (start != null && span.durationMs != null) {
      const computed = start + span.durationMs;
      if (computed > max) {
        max = computed;
      }
    }
  }
  return max;
}

function getBarColorClass(kind: string | null, statusCode: string | null): string {
  if (statusCode === 'ERROR') {
    return 'trace-waterfall-bar-error';
  }
  switch (kind) {
    case 'LLM':
      return 'trace-waterfall-bar-llm';
    case 'TOOL':
      return 'trace-waterfall-bar-tool';
    case 'OUTBOUND':
      return 'trace-waterfall-bar-outbound';
    case null:
    default:
      return 'trace-waterfall-bar-system';
  }
}


export function SessionTraceWaterfall({
  record,
  isExpanded,
  onExportSnapshotPayload,
  isExportingSnapshot,
  onToggleExpand,
}: SessionTraceWaterfallProps): ReactElement {
  const rows = useMemo(() => computeWaterfallRows(record), [record]);
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null);
  const [collapsedSpans, setCollapsedSpans] = useState<Set<string>>(() => new Set());

  const selectedSpan = useMemo(() => {
    if (selectedSpanId == null) {
      return null;
    }
    return record.spans.find((s) => s.spanId === selectedSpanId) ?? null;
  }, [record.spans, selectedSpanId]);

  const visibleRows = useMemo(() => {
    const hidden = new Set<string>();
    const parentMap = new Map<string, string | null>();
    for (const { span } of rows) {
      parentMap.set(span.spanId, span.parentSpanId);
    }

    for (const { span } of rows) {
      let parentId = span.parentSpanId;
      while (parentId != null) {
        if (collapsedSpans.has(parentId)) {
          hidden.add(span.spanId);
          break;
        }
        parentId = parentMap.get(parentId) ?? null;
      }
    }

    return rows.filter((row) => !hidden.has(row.span.spanId));
  }, [rows, collapsedSpans]);

  const hasChildren = useMemo(() => {
    const set = new Set<string>();
    for (const { span } of rows) {
      if (span.parentSpanId != null) {
        set.add(span.parentSpanId);
      }
    }
    return set;
  }, [rows]);

  const toggleCollapse = (spanId: string): void => {
    setCollapsedSpans((prev) => {
      const next = new Set(prev);
      if (next.has(spanId)) {
        next.delete(spanId);
      } else {
        next.add(spanId);
      }
      return next;
    });
  };

  const totalDuration = formatTraceDuration(record.spans.length > 0
    ? (record.spans[0].durationMs ?? null)
    : null);

  if (rows.length === 0) {
    return <div className="small text-body-secondary">No spans captured for this trace.</div>;
  }

  return (
    <div className="d-flex flex-column gap-0">
      <div
        className="trace-waterfall-header d-flex align-items-center gap-2 px-2 py-2"
        onClick={onToggleExpand}
        role="button"
      >
        <span className="trace-waterfall-toggle" style={{ fontSize: '0.8rem' }}>
          {isExpanded ? '\u25BE' : '\u25B8'}
        </span>
        <span className="fw-semibold">{record.traceName ?? record.traceId}</span>
        <Badge bg="secondary">{record.spans.length} spans</Badge>
        <Badge bg="secondary">{totalDuration}</Badge>
        {record.startedAt != null && (
          <span className="small text-body-secondary">{formatTraceTime(record.startedAt)}</span>
        )}
      </div>

      {isExpanded && (
        <>
          <div className="trace-waterfall-grid mt-2">
            {visibleRows.map((row) => {
              const isSelected = row.span.spanId === selectedSpanId;
              const isParent = hasChildren.has(row.span.spanId);
              const isCollapsed = collapsedSpans.has(row.span.spanId);

              return (
                <div
                  key={row.span.spanId}
                  className={`trace-waterfall-row ${isSelected ? 'trace-waterfall-row-selected' : ''}`}
                  onClick={() => setSelectedSpanId(row.span.spanId)}
                >
                  <div className="trace-waterfall-label" style={{ paddingLeft: `${row.depth * 16 + 4}px` }}>
                    {isParent ? (
                      <button
                        type="button"
                        className="trace-waterfall-toggle"
                        onClick={(e) => {
                          e.stopPropagation();
                          toggleCollapse(row.span.spanId);
                        }}
                      >
                        {isCollapsed ? '\u25B8' : '\u25BE'}
                      </button>
                    ) : (
                      <span className="trace-waterfall-toggle-spacer" />
                    )}
                    <span className="trace-waterfall-span-name text-truncate">
                      {row.span.name ?? row.span.spanId}
                    </span>
                    <Badge bg={getTraceStatusVariant(row.span.statusCode)} className="trace-waterfall-kind-badge">
                      {row.span.kind ?? '?'}
                    </Badge>
                  </div>

                  <div className="trace-waterfall-bar-container">
                    <div
                      className={`trace-waterfall-bar ${getBarColorClass(row.span.kind, row.span.statusCode)}`}
                      style={{ left: `${row.offsetPct}%`, width: `${row.widthPct}%` }}
                    >
                      <span className="trace-waterfall-bar-label">
                        {formatTraceDuration(row.span.durationMs)}
                      </span>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          {selectedSpan != null && (
            <SessionTraceSpanDetail
              span={selectedSpan}
              traceId={record.traceId}
              onExportSnapshotPayload={onExportSnapshotPayload}
              isExportingSnapshot={isExportingSnapshot}
              onClose={() => setSelectedSpanId(null)}
            />
          )}
        </>
      )}
    </div>
  );
}
