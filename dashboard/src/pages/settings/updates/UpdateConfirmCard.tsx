import type { ReactElement } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Form,
  Spinner,
} from 'react-bootstrap';
import type { SystemUpdateIntentResponse } from '../../../api/system';
import SettingsCardTitle from '../../../components/common/SettingsCardTitle';
import {
  formatUpdateTimestamp,
  formatUpdateOperation,
  getUpdateStateVariant,
  isRollbackOperation,
} from '../../../utils/systemUpdateUi';

export interface UpdateConfirmCardProps {
  intent: SystemUpdateIntentResponse | null;
  tokenInput: string;
  isBusy: boolean;
  isConfirmPending: boolean;
  onTokenChange: (value: string) => void;
  onConfirm: () => void;
  onClear: () => void;
  onCopyToken: () => void;
}

function isExpired(expiresAt: string): boolean {
  const timestamp = Date.parse(expiresAt);
  if (Number.isNaN(timestamp)) {
    return false;
  }
  return timestamp <= Date.now();
}

export function UpdateConfirmCard({
  intent,
  tokenInput,
  isBusy,
  isConfirmPending,
  onTokenChange,
  onConfirm,
  onClear,
  onCopyToken,
}: UpdateConfirmCardProps): ReactElement {
  if (intent == null) {
    return (
      <Card className="settings-card updates-card h-100">
        <Card.Body>
          <SettingsCardTitle title="Confirmation" />
          <div className="text-body-secondary small">
            Request apply or rollback intent to unlock final confirmation.
          </div>
        </Card.Body>
      </Card>
    );
  }

  const expired = isExpired(intent.expiresAt);
  const operationLabel = formatUpdateOperation(intent.operation);
  const isRollback = isRollbackOperation(intent.operation);
  const targetVersion = intent.targetVersion ?? (isRollback ? 'Image version' : 'Staged release');

  return (
    <Card className="settings-card updates-card h-100">
      <Card.Body>
        <SettingsCardTitle title="Confirmation" />
        <Alert variant={expired ? 'danger' : 'warning'} className="mb-3">
          <div className="small d-flex align-items-center gap-2 mb-1">
            <span className="fw-medium">Operation</span>
            <Badge bg={isRollback ? 'danger' : 'warning'} text={isRollback ? 'white' : 'dark'}>
              {operationLabel}
            </Badge>
          </div>
          <div className="small"><strong>Target:</strong> {targetVersion}</div>
          <div className="small"><strong>Expires:</strong> {formatUpdateTimestamp(intent.expiresAt)}</div>
          <div className="small text-body-secondary mt-1">
            Token is bound to this operation and target version.
          </div>
        </Alert>

        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium">Confirmation token</Form.Label>
          <Form.Control
            size="sm"
            type="text"
            value={tokenInput}
            onChange={(event) => onTokenChange(event.target.value)}
            className="font-monospace updates-token-input"
            placeholder="Enter confirmation token"
          />
        </Form.Group>

        <div className="d-flex flex-wrap gap-2">
          <Button
            type="button"
            size="sm"
            variant={isRollback ? 'danger' : getUpdateStateVariant('APPLYING')}
            onClick={onConfirm}
            disabled={isBusy || expired || tokenInput.trim().length === 0}
          >
            {isConfirmPending ? (
              <>
                <Spinner size="sm" animation="border" className="me-2" />
                Confirming...
              </>
            ) : (
              `Confirm ${operationLabel}`
            )}
          </Button>
          <Button type="button" size="sm" variant="outline-secondary" onClick={onCopyToken} disabled={tokenInput.trim().length === 0}>
            Copy token
          </Button>
          <Button type="button" size="sm" variant="secondary" onClick={onClear} disabled={isBusy}>
            Clear
          </Button>
        </div>
      </Card.Body>
    </Card>
  );
}
