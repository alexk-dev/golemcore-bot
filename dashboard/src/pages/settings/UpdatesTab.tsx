import { type ReactElement, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Col,
  Form,
  Row,
  Spinner,
  Table,
} from 'react-bootstrap';
import toast from 'react-hot-toast';
import type { SystemUpdateIntentResponse, SystemUpdateStatusResponse } from '../../api/system';
import {
  useApplySystemUpdate,
  useCheckSystemUpdate,
  useCreateSystemUpdateApplyIntent,
  useCreateSystemUpdateRollbackIntent,
  usePrepareSystemUpdate,
  useRollbackSystemUpdate,
  useSystemUpdateStatus,
} from '../../hooks/useSystem';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
const BUSY_STATES = new Set<string>(['CHECKING', 'PREPARING', 'APPLYING', 'VERIFYING']);

function formatTimestamp(value: string | null | undefined): string {
  if (value == null || value.trim().length === 0) {
    return 'N/A';
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString();
}

function stateVariant(state: string): string {
  if (state === 'FAILED') {
    return 'danger';
  }
  if (state === 'AVAILABLE' || state === 'STAGED') {
    return 'warning';
  }
  if (state === 'DISABLED') {
    return 'secondary';
  }
  return 'success';
}

interface StatusSummaryCardProps {
  status: SystemUpdateStatusResponse | null;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
}

function StatusSummaryCard({ status, isLoading, isError, onRetry }: StatusSummaryCardProps): ReactElement {
  if (isLoading) {
    return (
      <Card className="settings-card h-100">
        <Card.Body className="d-flex align-items-center justify-content-center">
          <Spinner size="sm" animation="border" />
        </Card.Body>
      </Card>
    );
  }

  if (isError || status == null) {
    return (
      <Card className="settings-card h-100">
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

  const current = status.current;
  const staged = status.staged;
  const available = status.available;

  return (
    <Card className="settings-card h-100">
      <Card.Body>
        <SettingsCardTitle title="Update Status" />
        <div className="mb-3 d-flex align-items-center gap-2">
          <span className="small text-body-secondary">State</span>
          <Badge bg={stateVariant(status.state)}>{status.state}</Badge>
        </div>

        <Table size="sm" responsive className="mb-0">
          <tbody>
            <tr>
              <th scope="row" className="text-body-secondary fw-normal">Current</th>
              <td>
                {current == null
                  ? 'N/A'
                  : `${current.version}${current.source != null && current.source.length > 0 ? ` (${current.source})` : ''}`}
              </td>
            </tr>
            <tr>
              <th scope="row" className="text-body-secondary fw-normal">Staged</th>
              <td>{staged?.version ?? 'None'}</td>
            </tr>
            <tr>
              <th scope="row" className="text-body-secondary fw-normal">Available</th>
              <td>{available?.version ?? 'None'}</td>
            </tr>
            <tr>
              <th scope="row" className="text-body-secondary fw-normal">Last check</th>
              <td>{formatTimestamp(status.lastCheckAt)}</td>
            </tr>
          </tbody>
        </Table>

        {status.lastError != null && status.lastError.trim().length > 0 && (
          <Alert variant="danger" className="mt-3 mb-0 small">
            {status.lastError}
          </Alert>
        )}
      </Card.Body>
    </Card>
  );
}

interface UpdateActionsCardProps {
  isBusy: boolean;
  canPrepare: boolean;
  canRequestApply: boolean;
  rollbackVersion: string;
  onRollbackVersionChange: (value: string) => void;
  onCheck: () => void;
  onPrepare: () => void;
  onCreateApplyIntent: () => void;
  onCreateRollbackIntent: () => void;
}

function UpdateActionsCard({
  isBusy,
  canPrepare,
  canRequestApply,
  rollbackVersion,
  onRollbackVersionChange,
  onCheck,
  onPrepare,
  onCreateApplyIntent,
  onCreateRollbackIntent,
}: UpdateActionsCardProps): ReactElement {
  return (
    <Card className="settings-card h-100">
      <Card.Body>
        <SettingsCardTitle title="Actions" tip="Prepare and apply updates with explicit confirmation" />

        <div className="d-flex flex-wrap gap-2 mb-3">
          <Button type="button" size="sm" variant="outline-primary" onClick={onCheck} disabled={isBusy}>
            Check
          </Button>
          <Button type="button" size="sm" variant="primary" onClick={onPrepare} disabled={isBusy || !canPrepare}>
            Prepare
          </Button>
          <Button type="button" size="sm" variant="warning" onClick={onCreateApplyIntent} disabled={isBusy || !canRequestApply}>
            Apply (request token)
          </Button>
        </div>

        <Form.Group>
          <Form.Label className="small fw-medium">Rollback target version (optional)</Form.Label>
          <Form.Control
            size="sm"
            type="text"
            value={rollbackVersion}
            placeholder="e.g. 0.3.0"
            onChange={(event) => onRollbackVersionChange(event.target.value)}
          />
          <div className="mt-2">
            <Button type="button" size="sm" variant="outline-danger" onClick={onCreateRollbackIntent} disabled={isBusy}>
              Rollback (request token)
            </Button>
          </div>
        </Form.Group>
      </Card.Body>
    </Card>
  );
}

interface IntentConfirmationCardProps {
  intent: SystemUpdateIntentResponse | null;
  tokenInput: string;
  isBusy: boolean;
  onTokenChange: (value: string) => void;
  onConfirm: () => void;
  onClear: () => void;
}

function IntentConfirmationCard({ intent, tokenInput, isBusy, onTokenChange, onConfirm, onClear }: IntentConfirmationCardProps): ReactElement {
  if (intent == null) {
    return (
      <Card className="settings-card h-100">
        <Card.Body>
          <SettingsCardTitle title="Confirmation" />
          <div className="text-body-secondary small">Request apply/rollback token to unlock confirmation.</div>
        </Card.Body>
      </Card>
    );
  }

  return (
    <Card className="settings-card h-100">
      <Card.Body>
        <SettingsCardTitle title="Confirmation" />
        <Alert variant="warning">
          <div className="small"><strong>Operation:</strong> {intent.operation}</div>
          <div className="small"><strong>Target:</strong> {intent.targetVersion ?? 'default rollback target'}</div>
          <div className="small"><strong>Token expires:</strong> {formatTimestamp(intent.expiresAt)}</div>
        </Alert>

        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium">Confirm token</Form.Label>
          <Form.Control
            size="sm"
            type="text"
            value={tokenInput}
            onChange={(event) => onTokenChange(event.target.value)}
            placeholder="Enter confirm token"
          />
        </Form.Group>

        <div className="d-flex gap-2">
          <Button type="button" size="sm" variant="danger" onClick={onConfirm} disabled={isBusy || tokenInput.trim().length === 0}>
            Confirm
          </Button>
          <Button type="button" size="sm" variant="secondary" onClick={onClear} disabled={isBusy}>
            Clear
          </Button>
        </div>
      </Card.Body>
    </Card>
  );
}

export function UpdatesTab(): ReactElement {
  const statusQuery = useSystemUpdateStatus();
  const checkMutation = useCheckSystemUpdate();
  const prepareMutation = usePrepareSystemUpdate();
  const applyIntentMutation = useCreateSystemUpdateApplyIntent();
  const applyMutation = useApplySystemUpdate();
  const rollbackIntentMutation = useCreateSystemUpdateRollbackIntent();
  const rollbackMutation = useRollbackSystemUpdate();

  const [rollbackVersion, setRollbackVersion] = useState<string>('');
  const [intent, setIntent] = useState<SystemUpdateIntentResponse | null>(null);
  const [tokenInput, setTokenInput] = useState<string>('');

  const status = statusQuery.data ?? null;
  const state = status?.state ?? 'UNAVAILABLE';
  const stagedVersion = status?.staged?.version ?? null;
  const availableVersion = status?.available?.version ?? null;

  const isOperationBusy = useMemo(() => {
    const hookBusy = checkMutation.isPending
      || prepareMutation.isPending
      || applyIntentMutation.isPending
      || applyMutation.isPending
      || rollbackIntentMutation.isPending
      || rollbackMutation.isPending;

    return hookBusy || BUSY_STATES.has(state);
  }, [
    applyIntentMutation.isPending,
    applyMutation.isPending,
    checkMutation.isPending,
    prepareMutation.isPending,
    rollbackIntentMutation.isPending,
    rollbackMutation.isPending,
    state,
  ]);

  const canPrepare = availableVersion != null || stagedVersion != null;
  const canRequestApply = stagedVersion != null;

  const handleCheck = async (): Promise<void> => {
    try {
      const result = await checkMutation.mutateAsync();
      toast.success(result.message);
    } catch (error: unknown) {
      toast.error(`Check failed: ${extractErrorMessage(error)}`);
    }
  };

  const handlePrepare = async (): Promise<void> => {
    try {
      const result = await prepareMutation.mutateAsync();
      toast.success(result.message);
    } catch (error: unknown) {
      toast.error(`Prepare failed: ${extractErrorMessage(error)}`);
    }
  };

  const handleCreateApplyIntent = async (): Promise<void> => {
    try {
      const result = await applyIntentMutation.mutateAsync();
      setIntent(result);
      setTokenInput(result.confirmToken);
      toast.success('Apply confirmation token created');
    } catch (error: unknown) {
      toast.error(`Unable to create apply intent: ${extractErrorMessage(error)}`);
    }
  };

  const handleCreateRollbackIntent = async (): Promise<void> => {
    try {
      const version = rollbackVersion.trim();
      const payload = version.length > 0 ? { version } : undefined;
      const result = await rollbackIntentMutation.mutateAsync(payload);
      setIntent(result);
      setTokenInput(result.confirmToken);
      toast.success('Rollback confirmation token created');
    } catch (error: unknown) {
      toast.error(`Unable to create rollback intent: ${extractErrorMessage(error)}`);
    }
  };

  const handleConfirmIntent = async (): Promise<void> => {
    if (intent == null) {
      return;
    }

    const token = tokenInput.trim();
    if (token.length === 0) {
      toast.error('Confirmation token is required');
      return;
    }

    try {
      const operation = intent.operation.toLowerCase();
      if (operation.includes('rollback')) {
        const version = rollbackVersion.trim();
        const payload = version.length > 0 ? { confirmToken: token, version } : { confirmToken: token };
        const result = await rollbackMutation.mutateAsync(payload);
        toast.success(result.message);
      } else {
        const result = await applyMutation.mutateAsync({ confirmToken: token });
        toast.success(result.message);
      }
      setIntent(null);
      setTokenInput('');
    } catch (error: unknown) {
      toast.error(`Operation failed: ${extractErrorMessage(error)}`);
    }
  };

  return (
    <section>
      <Row className="g-3 mb-3">
        <Col lg={4}>
          <StatusSummaryCard
            status={status}
            isLoading={statusQuery.isLoading}
            isError={statusQuery.isError}
            onRetry={() => { void statusQuery.refetch(); }}
          />
        </Col>
        <Col lg={4}>
          <UpdateActionsCard
            isBusy={isOperationBusy}
            canPrepare={canPrepare}
            canRequestApply={canRequestApply}
            rollbackVersion={rollbackVersion}
            onRollbackVersionChange={setRollbackVersion}
            onCheck={() => { void handleCheck(); }}
            onPrepare={() => { void handlePrepare(); }}
            onCreateApplyIntent={() => { void handleCreateApplyIntent(); }}
            onCreateRollbackIntent={() => { void handleCreateRollbackIntent(); }}
          />
        </Col>
        <Col lg={4}>
          <IntentConfirmationCard
            intent={intent}
            tokenInput={tokenInput}
            isBusy={isOperationBusy}
            onTokenChange={setTokenInput}
            onConfirm={() => { void handleConfirmIntent(); }}
            onClear={() => {
              setIntent(null);
              setTokenInput('');
            }}
          />
        </Col>
      </Row>
    </section>
  );
}
