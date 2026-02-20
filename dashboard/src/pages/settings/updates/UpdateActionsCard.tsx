import type { ReactElement } from 'react';
import {
  Alert,
  Button,
  Card,
  Form,
  Spinner,
} from 'react-bootstrap';
import SettingsCardTitle from '../../../components/common/SettingsCardTitle';

export interface UpdateActionsCardProps {
  isBusy: boolean;
  isEnabled: boolean;
  canPrepare: boolean;
  canRequestApply: boolean;
  rollbackVersion: string;
  isChecking: boolean;
  isPreparing: boolean;
  isApplyIntentPending: boolean;
  isRollbackIntentPending: boolean;
  onRollbackVersionChange: (value: string) => void;
  onCheck: () => void;
  onPrepare: () => void;
  onCreateApplyIntent: () => void;
  onCreateRollbackIntent: () => void;
}

function LoadingLabel({ text }: { text: string }): ReactElement {
  return (
    <>
      <Spinner size="sm" animation="border" className="me-2" />
      {text}
    </>
  );
}

export function UpdateActionsCard({
  isBusy,
  isEnabled,
  canPrepare,
  canRequestApply,
  rollbackVersion,
  isChecking,
  isPreparing,
  isApplyIntentPending,
  isRollbackIntentPending,
  onRollbackVersionChange,
  onCheck,
  onPrepare,
  onCreateApplyIntent,
  onCreateRollbackIntent,
}: UpdateActionsCardProps): ReactElement {
  return (
    <Card className="settings-card updates-card h-100">
      <Card.Body>
        <SettingsCardTitle title="Actions" tip="Controlled flow: check -> prepare -> apply with token confirmation" />

        {!isEnabled && (
          <Alert variant="secondary" className="small mb-3">
            Updates are disabled by backend configuration.
          </Alert>
        )}

        <div className="updates-step mb-3">
          <div className="d-flex align-items-center justify-content-between gap-3 mb-1">
            <div className="fw-medium small">1. Check latest release</div>
            <Button
              type="button"
              size="sm"
              variant="outline-primary"
              onClick={onCheck}
              disabled={!isEnabled || isBusy}
            >
              {isChecking ? <LoadingLabel text="Checking..." /> : 'Check'}
            </Button>
          </div>
          <div className="small text-body-secondary">
            Reads latest GitHub release and compares it with current runtime version.
          </div>
        </div>

        <div className="updates-step mb-3">
          <div className="d-flex align-items-center justify-content-between gap-3 mb-1">
            <div className="fw-medium small">2. Stage update artifact</div>
            <Button
              type="button"
              size="sm"
              variant="primary"
              onClick={onPrepare}
              disabled={!isEnabled || isBusy || !canPrepare}
            >
              {isPreparing ? <LoadingLabel text="Preparing..." /> : 'Prepare'}
            </Button>
          </div>
          <div className="small text-body-secondary">
            Downloads and verifies checksum. Enabled when a compatible update is available.
          </div>
        </div>

        <div className="updates-step mb-3">
          <div className="d-flex align-items-center justify-content-between gap-3 mb-1">
            <div className="fw-medium small">3. Request apply token</div>
            <Button
              type="button"
              size="sm"
              variant="warning"
              onClick={onCreateApplyIntent}
              disabled={!isEnabled || isBusy || !canRequestApply}
            >
              {isApplyIntentPending ? <LoadingLabel text="Requesting..." /> : 'Apply Intent'}
            </Button>
          </div>
          <div className="small text-body-secondary">
            Creates short-lived confirmation token to apply the staged version.
          </div>
        </div>

        <Form.Group className="updates-step">
          <Form.Label className="small fw-medium mb-1">Rollback target version (optional)</Form.Label>
          <div className="d-flex gap-2">
            <Form.Control
              size="sm"
              type="text"
              value={rollbackVersion}
              placeholder="e.g. 0.3.0"
              onChange={(event) => onRollbackVersionChange(event.target.value)}
            />
            <Button
              type="button"
              size="sm"
              variant="outline-danger"
              onClick={onCreateRollbackIntent}
              disabled={!isEnabled || isBusy}
            >
              {isRollbackIntentPending ? <LoadingLabel text="Requesting..." /> : 'Rollback Intent'}
            </Button>
          </div>
          <div className="small text-body-secondary mt-1">
            Leave empty to rollback to image version. Enter version to rollback to cached jar.
          </div>
        </Form.Group>
      </Card.Body>
    </Card>
  );
}
