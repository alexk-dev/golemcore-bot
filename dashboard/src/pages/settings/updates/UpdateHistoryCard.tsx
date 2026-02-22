import type { ReactElement } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Spinner,
  Table,
} from 'react-bootstrap';
import type { SystemUpdateHistoryItem } from '../../../api/system';
import SettingsCardTitle from '../../../components/common/SettingsCardTitle';
import {
  formatUpdateOperation,
  formatUpdateTimestamp,
  getUpdateResultVariant,
} from '../../../utils/systemUpdateUi';

export interface UpdateHistoryCardProps {
  items: SystemUpdateHistoryItem[];
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
}

export function UpdateHistoryCard({
  items,
  isLoading,
  isError,
  onRetry,
}: UpdateHistoryCardProps): ReactElement {
  return (
    <Card className="settings-card updates-card">
      <Card.Body>
        <div className="d-flex align-items-center justify-content-between gap-2 mb-3">
          <SettingsCardTitle title="Recent Activity" className="mb-0" tip="Latest update actions from backend history buffer" />
          <Button type="button" size="sm" variant="outline-secondary" onClick={onRetry} disabled={isLoading}>
            {isLoading ? 'Refreshing...' : 'Refresh'}
          </Button>
        </div>

        {isLoading && (
          <div className="d-flex align-items-center gap-2 small text-body-secondary">
            <Spinner size="sm" animation="border" />
            Loading history...
          </div>
        )}

        {!isLoading && isError && (
          <Alert variant="warning" className="mb-0">
            Unable to load update history.
          </Alert>
        )}

        {!isLoading && !isError && items.length === 0 && (
          <div className="small text-body-secondary">No update actions yet.</div>
        )}

        {!isLoading && !isError && items.length > 0 && (
          <Table size="sm" responsive className="mb-0 dashboard-table responsive-table updates-history-table">
            <thead>
              <tr>
                <th scope="col">Time</th>
                <th scope="col">Operation</th>
                <th scope="col">Version</th>
                <th scope="col">Result</th>
                <th scope="col">Message</th>
              </tr>
            </thead>
            <tbody>
              {items.slice(0, 12).map((item) => (
                <tr key={`${item.timestamp}-${item.operation}-${item.version ?? 'none'}-${item.result}`}>
                  <td data-label="Time" className="small">{formatUpdateTimestamp(item.timestamp)}</td>
                  <td data-label="Operation">
                    <Badge bg="secondary">{formatUpdateOperation(item.operation)}</Badge>
                  </td>
                  <td data-label="Version" className="small font-monospace">{item.version ?? 'N/A'}</td>
                  <td data-label="Result">
                    <Badge bg={getUpdateResultVariant(item.result)}>{item.result}</Badge>
                  </td>
                  <td data-label="Message" className="small">{item.message ?? '-'}</td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card.Body>
    </Card>
  );
}
