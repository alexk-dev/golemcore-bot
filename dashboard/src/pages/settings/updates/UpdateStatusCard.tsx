import type { ReactElement } from 'react';
import { Alert, Badge, Button, Card, Spinner } from 'react-bootstrap';
import type { SystemUpdateStatusResponse, SystemUpdateVersionInfo } from '../../../api/system';
import SettingsCardTitle from '../../../components/common/SettingsCardTitle';
import {
  formatUpdateTimestamp,
  getUpdateStateDescription,
  getUpdateStateLabel,
  getUpdateStateVariant,
} from '../../../utils/systemUpdateUi';

export interface UpdateStatusCardProps {
  status: SystemUpdateStatusResponse | null;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
}

function formatVersionLine(info: SystemUpdateVersionInfo | null, fallback: string): string {
  if (info == null) {
    return fallback;
  }

  const source = info.source != null && info.source.trim().length > 0 ? ` (${info.source})` : '';
  return `${info.version}${source}`;
}

export function UpdateStatusCard({
  status,
  isLoading,
  isError,
  onRetry,
}: UpdateStatusCardProps): ReactElement {
  if (isLoading) {
    return (
      <Card className="settings-card updates-card h-100">
        <Card.Body className="d-flex flex-column align-items-center justify-content-center gap-2">
          <Spinner size="sm" animation="border" />
          <div className="small text-body-secondary">Loading update status...</div>
        </Card.Body>
      </Card>
    );
  }

  if (isError || status == null) {
    return (
      <Card className="settings-card updates-card h-100">
        <Card.Body>
          <SettingsCardTitle title="Update Status" />
          <Alert variant="warning" className="mb-3">
            Update API is unavailable in this backend version.
          </Alert>
          <Button type="button" variant="secondary" size="sm" onClick={onRetry}>Retry</Button>
        </Card.Body>
      </Card>
    );
  }

  return (
    <Card className="settings-card updates-card h-100">
      <Card.Body>
        <SettingsCardTitle title="Update Status" />
        <div className="mb-2 d-flex align-items-center gap-2">
          <span className="small text-body-secondary">State</span>
          <Badge bg={getUpdateStateVariant(status.state)}>{getUpdateStateLabel(status.state)}</Badge>
        </div>
        <div className="small text-body-secondary mb-3">{getUpdateStateDescription(status.state)}</div>

        <dl className="updates-meta-list mb-0">
          <dt>Feature</dt>
          <dd>{status.enabled ? 'Enabled' : 'Disabled'}</dd>

          <dt>Current</dt>
          <dd>{formatVersionLine(status.current, 'N/A')}</dd>

          <dt>Staged</dt>
          <dd>{formatVersionLine(status.staged, 'None')}</dd>

          <dt>Available</dt>
          <dd>{formatVersionLine(status.available, 'None')}</dd>

          <dt>Last check</dt>
          <dd>{formatUpdateTimestamp(status.lastCheckAt)}</dd>
        </dl>

        {status.staged?.preparedAt != null && (
          <div className="small text-body-secondary mt-3">
            Staged at: <span className="text-body">{formatUpdateTimestamp(status.staged.preparedAt)}</span>
          </div>
        )}
        {status.available?.publishedAt != null && (
          <div className="small text-body-secondary">
            Published: <span className="text-body">{formatUpdateTimestamp(status.available.publishedAt)}</span>
          </div>
        )}

        {status.lastError != null && status.lastError.trim().length > 0 && (
          <Alert variant="danger" className="mt-3 mb-0 small">
            {status.lastError}
          </Alert>
        )}
      </Card.Body>
    </Card>
  );
}
