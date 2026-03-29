import { useState, type ReactElement } from 'react';
import { Badge, Button, Card, Table } from 'react-bootstrap';
import toast from 'react-hot-toast';

import type { SessionTraceSpan } from '../../api/sessions';
import { formatTraceBytes, formatTraceDuration, formatTraceTimestamp, getTraceStatusVariant } from '../../lib/traceFormat';

export interface SessionTraceSpanDetailProps {
  span: SessionTraceSpan;
  traceId: string;
  onClose: () => void;
}

type DetailTab = 'attributes' | 'events' | 'snapshots';

function AttributesSection({ span }: { span: SessionTraceSpan }): ReactElement {
  const entries = Object.entries(span.attributes).filter(
    ([, value]) => value != null && String(value).length > 0,
  );

  if (entries.length === 0) {
    return <div className="small text-body-secondary">No attributes recorded.</div>;
  }

  return (
    <Table size="sm" className="dashboard-table mb-0 small">
      <thead>
        <tr>
          <th scope="col">Key</th>
          <th scope="col">Value</th>
        </tr>
      </thead>
      <tbody>
        {entries.map(([key, value]) => (
          <tr key={key}>
            <td className="text-nowrap fw-medium">{key}</td>
            <td className="text-break">{String(value)}</td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function EventsSection({ span }: { span: SessionTraceSpan }): ReactElement {
  if (span.events.length === 0) {
    return <div className="small text-body-secondary">No events recorded.</div>;
  }

  return (
    <div className="d-flex flex-column gap-2">
      {span.events.map((event, index) => (
        <div key={`${event.name ?? 'event'}-${index}`} className="d-flex flex-column gap-1 rounded bg-body-tertiary p-2">
          <div className="d-flex align-items-center gap-2">
            <Badge bg="secondary">{event.name ?? 'event'}</Badge>
            {event.timestamp != null && (
              <span className="small text-body-secondary">{formatTraceTimestamp(event.timestamp)}</span>
            )}
          </div>
          {Object.entries(event.attributes).length > 0 && (
            <div className="small text-body-secondary">
              {Object.entries(event.attributes)
                .filter(([, v]) => v != null)
                .map(([k, v]) => `${k}=${String(v)}`)
                .join(', ')}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

function exportPayloadAsJson(payload: string, role: string | null, spanName: string | null): void {
  let formatted: string;
  try {
    const parsed: unknown = JSON.parse(payload);
    formatted = JSON.stringify(parsed, null, 2);
  } catch {
    formatted = payload;
  }
  const blob = new Blob([formatted], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `payload-${spanName ?? 'span'}-${role ?? 'snapshot'}.json`;
  link.click();
  URL.revokeObjectURL(url);
  toast.success('Payload exported');
}

function SnapshotsSection({ span }: { span: SessionTraceSpan }): ReactElement {
  if (span.snapshots.length === 0) {
    return <div className="small text-body-secondary">No snapshots stored.</div>;
  }

  return (
    <div className="d-flex flex-column gap-2">
      {span.snapshots.map((snapshot) => (
        <div key={snapshot.snapshotId} className="rounded bg-body-tertiary p-2">
          <div className="d-flex flex-wrap align-items-center gap-2 mb-1">
            <Badge bg="secondary">{snapshot.role ?? 'snapshot'}</Badge>
            {snapshot.contentType != null && <Badge bg="info">{snapshot.contentType}</Badge>}
            <span className="small text-body-secondary">
              {formatTraceBytes(snapshot.compressedSize)} / {formatTraceBytes(snapshot.originalSize)}
            </span>
            {snapshot.payloadPreviewTruncated && <Badge bg="warning">truncated</Badge>}
            {snapshot.payloadAvailable && snapshot.payloadPreview != null && (
              <Button
                type="button"
                size="sm"
                variant="secondary"
                className="py-0 px-2"
                onClick={() => {
                  const preview = snapshot.payloadPreview;
                  if (preview != null) {
                    exportPayloadAsJson(preview, snapshot.role, span.name);
                  }
                }}
              >
                Export JSON
              </Button>
            )}
          </div>
          {snapshot.payloadAvailable && snapshot.payloadPreview != null ? (
            <pre className="logs-detail-pre small mb-0 session-trace-payload-pre">{snapshot.payloadPreview}</pre>
          ) : (
            <div className="small text-body-secondary">Payload preview unavailable.</div>
          )}
        </div>
      ))}
    </div>
  );
}

export function SessionTraceSpanDetail({ span, traceId, onClose }: SessionTraceSpanDetailProps): ReactElement {
  const [activeTab, setActiveTab] = useState<DetailTab>('attributes');

  const tabs: Array<{ key: DetailTab; label: string; count: number }> = [
    { key: 'attributes', label: 'Attributes', count: Object.keys(span.attributes).length },
    { key: 'events', label: 'Events', count: span.events.length },
    { key: 'snapshots', label: 'Snapshots', count: span.snapshots.length },
  ];

  return (
    <Card className="trace-waterfall-detail mt-2">
      <Card.Body className="p-3">
        <div className="d-flex justify-content-between align-items-start gap-2 mb-2">
          <div className="d-flex flex-column gap-1">
            <div className="d-flex align-items-center gap-2">
              <span className="fw-semibold">{span.name ?? span.spanId}</span>
              <Badge bg={getTraceStatusVariant(span.statusCode)}>{span.statusCode ?? 'UNKNOWN'}</Badge>
              <Badge bg="secondary">{span.kind ?? 'UNKNOWN'}</Badge>
              <Badge bg="secondary">{formatTraceDuration(span.durationMs)}</Badge>
            </div>
            <div className="small text-body-secondary d-flex flex-wrap gap-3">
              <span>span: <code>{span.spanId}</code></span>
              {span.parentSpanId != null && <span>parent: <code>{span.parentSpanId}</code></span>}
              <span>trace: <code>{traceId}</code></span>
              <span>{formatTraceTimestamp(span.startedAt)} &mdash; {formatTraceTimestamp(span.endedAt)}</span>
            </div>
          </div>
          <Button type="button" size="sm" variant="secondary" onClick={onClose} className="flex-shrink-0">
            &times;
          </Button>
        </div>

        <div className="d-flex gap-1 mb-2">
          {tabs.map((tab) => (
            <Button
              key={tab.key}
              type="button"
              size="sm"
              variant={activeTab === tab.key ? 'primary' : 'secondary'}
              onClick={() => setActiveTab(tab.key)}
            >
              {tab.label} ({tab.count})
            </Button>
          ))}
        </div>

        <div className="trace-waterfall-detail-body">
          {activeTab === 'attributes' && <AttributesSection span={span} />}
          {activeTab === 'events' && <EventsSection span={span} />}
          {activeTab === 'snapshots' && <SnapshotsSection span={span} />}
        </div>
      </Card.Body>
    </Card>
  );
}
