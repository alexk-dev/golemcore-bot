import type { ReactElement } from 'react';
import { Badge, Card } from 'react-bootstrap';

import type { SessionTraceSpan } from '../../api/sessions';
import { formatTraceBytes } from '../../lib/traceFormat';

interface SessionTraceSnapshotViewerProps {
  span: SessionTraceSpan | null;
}

export function SessionTraceSnapshotViewer({ span }: SessionTraceSnapshotViewerProps): ReactElement {
  return (
    <Card className="settings-card h-100">
      <Card.Body>
        <div className="d-flex justify-content-between align-items-center gap-2 mb-3">
          <h3 className="h6 mb-0">Payload preview</h3>
          {span != null && <Badge bg="secondary">{span.name ?? span.spanId}</Badge>}
        </div>
        {span == null ? (
          <div className="small text-body-secondary">Select a span to inspect captured payloads.</div>
        ) : span.snapshots.length === 0 ? (
          <div className="small text-body-secondary">No snapshots were stored for this span.</div>
        ) : (
          <div className="d-flex flex-column gap-3">
            {span.snapshots.map((snapshot) => (
              <Card key={snapshot.snapshotId} className="bg-body-tertiary border-0">
                <Card.Body>
                  <div className="d-flex flex-wrap align-items-center gap-2 mb-2">
                    <Badge bg="secondary">{snapshot.role ?? 'snapshot'}</Badge>
                    {snapshot.contentType != null && <Badge bg="info">{snapshot.contentType}</Badge>}
                    <span className="small text-body-secondary">
                      {formatTraceBytes(snapshot.compressedSize)} compressed / {formatTraceBytes(snapshot.originalSize)} raw
                    </span>
                    {snapshot.payloadPreviewTruncated && <Badge bg="warning">Preview truncated</Badge>}
                  </div>
                  {snapshot.payloadAvailable && snapshot.payloadPreview != null ? (
                    <pre className="logs-detail-pre small mb-0 session-trace-payload-pre">{snapshot.payloadPreview}</pre>
                  ) : (
                    <div className="small text-body-secondary">Payload preview unavailable for this snapshot.</div>
                  )}
                </Card.Body>
              </Card>
            ))}
          </div>
        )}
      </Card.Body>
    </Card>
  );
}
