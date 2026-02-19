import type { ReactElement } from 'react';
import { Badge, Card } from 'react-bootstrap';
import type { LogEntryResponse } from '../../api/system';
import { levelVariant } from './logUtils';

export interface LogsDetailsCardProps {
  selectedEntry: LogEntryResponse | null;
}

export function LogsDetailsCard({ selectedEntry }: LogsDetailsCardProps): ReactElement {
  return (
    <Card className="h-100">
      <Card.Header className="small text-body-secondary">
        {selectedEntry != null ? `Selected seq=${selectedEntry.seq}` : 'Select a row to inspect full payload'}
      </Card.Header>
      <Card.Body className="logs-details">
        {selectedEntry == null ? (
          <div className="text-body-secondary">No entry selected.</div>
        ) : (
          <>
            <div className="logs-detail-meta mb-2">
              <Badge bg={levelVariant(selectedEntry.level)}>{selectedEntry.level}</Badge>
              <code>{selectedEntry.timestamp}</code>
              {selectedEntry.thread != null && <code>{selectedEntry.thread}</code>}
              {selectedEntry.logger != null && <code>{selectedEntry.logger}</code>}
            </div>
            <pre className="logs-detail-pre">{selectedEntry.message ?? ''}</pre>
            {selectedEntry.exception != null && selectedEntry.exception.length > 0 && (
              <>
                <div className="small fw-semibold mt-3 mb-1">Exception</div>
                <pre className="logs-detail-pre text-danger-emphasis">{selectedEntry.exception}</pre>
              </>
            )}
          </>
        )}
      </Card.Body>
    </Card>
  );
}
