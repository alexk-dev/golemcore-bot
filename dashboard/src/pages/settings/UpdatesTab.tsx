import { type ReactElement, useState } from 'react';
import { Alert, Col, Row } from 'react-bootstrap';
import type { UseMutationResult } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import type {
  RollbackConfirmRequest,
  SystemUpdateActionResponse,
  SystemUpdateIntentResponse,
} from '../../api/system';
import {
  useApplySystemUpdate,
  useCheckSystemUpdate,
  useCreateSystemUpdateApplyIntent,
  useCreateSystemUpdateRollbackIntent,
  usePrepareSystemUpdate,
  useRollbackSystemUpdate,
  useSystemUpdateHistory,
  useSystemUpdateStatus,
} from '../../hooks/useSystem';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import { copyTextToClipboard } from '../../utils/clipboard';
import { UPDATE_BUSY_STATES, isRollbackOperation } from '../../utils/systemUpdateUi';
import { UpdateActionsCard } from './updates/UpdateActionsCard';
import { UpdateConfirmCard } from './updates/UpdateConfirmCard';
import { UpdateHistoryCard } from './updates/UpdateHistoryCard';
import { UpdateStatusCard } from './updates/UpdateStatusCard';
import {
  buildRollbackConfirmPayload,
  buildRollbackIntentPayload,
  isIntentExpired,
  resolveCanPrepare,
  resolveCanRequestApply,
  resolveConfirmPending,
  resolveEnabled,
  resolveHistoryLoading,
  resolveOperationBusy,
} from './updates/updateFlow';

async function runActionWithToast<T>(
  action: () => Promise<T>,
  onSuccess: (result: T) => void,
  errorPrefix: string,
): Promise<void> {
  try {
    const result = await action();
    onSuccess(result);
  } catch (error: unknown) {
    toast.error(`${errorPrefix}: ${extractErrorMessage(error)}`);
  }
}

async function confirmIntentAction(
  intent: SystemUpdateIntentResponse,
  token: string,
  applyMutation: UseMutationResult<SystemUpdateActionResponse, unknown, { confirmToken: string }>,
  rollbackMutation: UseMutationResult<SystemUpdateActionResponse, unknown, RollbackConfirmRequest>,
): Promise<SystemUpdateActionResponse> {
  if (isRollbackOperation(intent.operation)) {
    return rollbackMutation.mutateAsync(buildRollbackConfirmPayload(intent, token));
  }

  return applyMutation.mutateAsync({ confirmToken: token });
}

export function UpdatesTab(): ReactElement {
  const statusQuery = useSystemUpdateStatus();
  const historyQuery = useSystemUpdateHistory();
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
  const isEnabled = resolveEnabled(status, statusQuery.isError);
  const stagedVersion = status?.staged?.version ?? null;
  const availableVersion = status?.available?.version ?? null;
  const canPrepare = resolveCanPrepare(availableVersion, stagedVersion);
  const canRequestApply = resolveCanRequestApply(stagedVersion);
  const pendingFlags = [
    checkMutation.isPending,
    prepareMutation.isPending,
    applyIntentMutation.isPending,
    applyMutation.isPending,
    rollbackIntentMutation.isPending,
    rollbackMutation.isPending,
  ];
  const isOperationBusy = resolveOperationBusy(pendingFlags, state);
  const isConfirmPending = resolveConfirmPending(applyMutation.isPending, rollbackMutation.isPending);
  const isHistoryLoading = resolveHistoryLoading(historyQuery.isLoading, historyQuery.isFetching);
  const showBusyBanner = UPDATE_BUSY_STATES.has(state);

  const handleCheck = (): Promise<void> => runActionWithToast<SystemUpdateActionResponse>(
    () => checkMutation.mutateAsync(),
    (result) => {
      toast.success(result.message);
    },
    'Check failed',
  );

  const handlePrepare = (): Promise<void> => runActionWithToast<SystemUpdateActionResponse>(
    () => prepareMutation.mutateAsync(),
    (result) => {
      toast.success(result.message);
    },
    'Prepare failed',
  );

  const handleCreateApplyIntent = (): Promise<void> => runActionWithToast<SystemUpdateIntentResponse>(
    () => applyIntentMutation.mutateAsync(),
    (result) => {
      setIntent(result);
      setTokenInput(result.confirmToken);
      toast.success('Apply confirmation token created');
    },
    'Unable to create apply intent',
  );

  const handleCreateRollbackIntent = (): Promise<void> => runActionWithToast<SystemUpdateIntentResponse>(
    () => rollbackIntentMutation.mutateAsync(buildRollbackIntentPayload(rollbackVersion)),
    (result) => {
      setIntent(result);
      setTokenInput(result.confirmToken);
      setRollbackVersion(result.targetVersion ?? '');
      toast.success('Rollback confirmation token created');
    },
    'Unable to create rollback intent',
  );

  const handleCopyToken = async (): Promise<void> => {
    const token = tokenInput.trim();
    if (token.length === 0) {
      toast.error('No token to copy');
      return;
    }

    try {
      const copied = await copyTextToClipboard(token);
      if (!copied) {
        toast.error('Unable to copy token');
        return;
      }
      toast.success('Token copied');
    } catch (error: unknown) {
      toast.error(`Unable to copy token: ${extractErrorMessage(error)}`);
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
    if (isIntentExpired(intent.expiresAt)) {
      toast.error('Confirmation token expired. Request a new intent.');
      return;
    }

    try {
      const result = await confirmIntentAction(intent, token, applyMutation, rollbackMutation);
      toast.success(result.message);
      setIntent(null);
      setTokenInput('');
    } catch (error: unknown) {
      toast.error(`Operation failed: ${extractErrorMessage(error)}`);
    }
  };

  return (
    <section className="updates-tab">
      {showBusyBanner && (
        <Alert variant="info" className="small mb-3">
          Update service is currently <strong>{state.toLowerCase()}</strong>. Actions are temporarily locked.
        </Alert>
      )}

      <Row className="g-3 mb-3">
        <Col xl={4}>
          <UpdateStatusCard
            status={status}
            isLoading={statusQuery.isLoading}
            isError={statusQuery.isError}
            onRetry={() => { void statusQuery.refetch(); }}
          />
        </Col>
        <Col xl={4}>
          <UpdateActionsCard
            isBusy={isOperationBusy}
            isEnabled={isEnabled}
            canPrepare={canPrepare}
            canRequestApply={canRequestApply}
            rollbackVersion={rollbackVersion}
            isChecking={checkMutation.isPending}
            isPreparing={prepareMutation.isPending}
            isApplyIntentPending={applyIntentMutation.isPending}
            isRollbackIntentPending={rollbackIntentMutation.isPending}
            onRollbackVersionChange={setRollbackVersion}
            onCheck={() => { void handleCheck(); }}
            onPrepare={() => { void handlePrepare(); }}
            onCreateApplyIntent={() => { void handleCreateApplyIntent(); }}
            onCreateRollbackIntent={() => { void handleCreateRollbackIntent(); }}
          />
        </Col>
        <Col xl={4}>
          <UpdateConfirmCard
            intent={intent}
            tokenInput={tokenInput}
            isBusy={isOperationBusy}
            isConfirmPending={isConfirmPending}
            onTokenChange={setTokenInput}
            onConfirm={() => { void handleConfirmIntent(); }}
            onClear={() => {
              setIntent(null);
              setTokenInput('');
            }}
            onCopyToken={() => { void handleCopyToken(); }}
          />
        </Col>
      </Row>

      <UpdateHistoryCard
        items={historyQuery.data ?? []}
        isLoading={isHistoryLoading}
        isError={historyQuery.isError}
        onRetry={() => { void historyQuery.refetch(); }}
      />
    </section>
  );
}
