import type { ReactElement } from 'react';
import { Badge, Card, Table } from 'react-bootstrap';

import type { SessionTraceSpan } from '../../api/sessions';
import { formatTraceDuration, getTraceStatusVariant } from '../../lib/traceFormat';

interface SessionTraceTimelineProps {
  spans: SessionTraceSpan[];
  activeSpanId: string | null;
  onSelectSpan: (spanId: string) => void;
}

function formatTimestamp(value: string | null): string {
  if (value == null || value.length === 0) {
    return '-';
  }
  return new Date(value).toLocaleTimeString();
}

export function SessionTraceTimeline({
  spans,
  activeSpanId,
  onSelectSpan,
}: SessionTraceTimelineProps): ReactElement {
  return (
    <Card className="settings-card">
      <Card.Body>
        <h3 className="h6 mb-3">Timeline</h3>
        <Table size="sm" hover responsive className="dashboard-table mb-0">
          <thead>
            <tr>
              <th scope="col">Span</th>
              <th scope="col">Kind</th>
              <th scope="col">Started</th>
              <th scope="col">Duration</th>
              <th scope="col">Status</th>
            </tr>
          </thead>
          <tbody>
            {spans.length > 0 ? spans.map((span) => (
              <tr
                key={span.spanId}
                className={span.spanId === activeSpanId ? 'table-active' : undefined}
                onClick={() => onSelectSpan(span.spanId)}
              >
                <td>{span.name ?? span.spanId}</td>
                <td>{span.kind ?? '-'}</td>
                <td>{formatTimestamp(span.startedAt)}</td>
                <td>{formatTraceDuration(span.durationMs)}</td>
                <td><Badge bg={getTraceStatusVariant(span.statusCode)}>{span.statusCode ?? 'UNKNOWN'}</Badge></td>
              </tr>
            )) : (
              <tr>
                <td colSpan={5} className="text-body-secondary">No spans captured for this trace.</td>
              </tr>
            )}
          </tbody>
        </Table>
      </Card.Body>
    </Card>
  );
}
