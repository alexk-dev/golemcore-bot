import type { ReactElement } from 'react';
import { Badge, Button, Card } from '../ui/tailwind-components';

import type { SessionTraceSpan } from '../../api/sessions';
import {
  buildTraceTree,
  flattenTraceTree,
  formatTraceDuration,
  getIndentClass,
  getTraceStatusVariant,
} from '../../lib/traceFormat';

interface SessionTraceTreeProps {
  spans: SessionTraceSpan[];
  activeSpanId: string | null;
  onSelectSpan: (spanId: string) => void;
}

export function SessionTraceTree({
  spans,
  activeSpanId,
  onSelectSpan,
}: SessionTraceTreeProps): ReactElement {
  const rows = flattenTraceTree(buildTraceTree(spans));

  return (
    <Card className="settings-card h-100">
      <Card.Body>
        <h3 className="h6 mb-3">Span tree</h3>
        {rows.length === 0 ? (
          <div className="small text-body-secondary">No spans captured for this trace.</div>
        ) : (
          <div className="d-flex flex-column gap-2">
            {rows.map(({ span, depth }) => (
              <Button
                key={span.spanId}
                type="button"
                size="sm"
                variant={span.spanId === activeSpanId ? 'primary' : 'secondary'}
                className={`text-start ${getIndentClass(depth)}`.trim()}
                onClick={() => onSelectSpan(span.spanId)}
              >
                <div className="d-flex justify-content-between align-items-center gap-2">
                  <div className="d-flex flex-column">
                    <span>{span.name ?? span.spanId}</span>
                    <span className="small opacity-75">{span.kind ?? 'UNKNOWN'}</span>
                  </div>
                  <div className="d-flex align-items-center gap-2">
                    <Badge bg={getTraceStatusVariant(span.statusCode)}>{span.statusCode ?? 'UNKNOWN'}</Badge>
                    <span className="small">{formatTraceDuration(span.durationMs)}</span>
                  </div>
                </div>
              </Button>
            ))}
          </div>
        )}
      </Card.Body>
    </Card>
  );
}
