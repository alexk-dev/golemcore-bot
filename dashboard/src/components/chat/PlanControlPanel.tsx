import { Badge, Button, Spinner } from 'react-bootstrap';
import type { PlanControlState, PlanSummary } from '../../api/plans';
import {
  useApprovePlan,
  useCancelPlan,
  useDisablePlanMode,
  useDonePlanMode,
  useEnablePlanMode,
  usePlanActionsPending,
  usePlanControlState,
  useResumePlan,
} from '../../hooks/usePlans';

interface Props {
  chatSessionId: string;
}

interface PlanControlHeaderProps {
  disabled: boolean;
  onRefresh: () => void;
}

interface PlanControlBodyProps {
  data: PlanControlState | undefined;
  isLoading: boolean;
  isError: boolean;
  actionPending: boolean;
  onEnable: () => void;
  onDone: () => void;
  onDisable: () => void;
  onApprove: (planId: string) => void;
  onCancel: (planId: string) => void;
  onResume: (planId: string) => void;
}

interface PlanModeActionsProps {
  planModeActive: boolean;
  actionPending: boolean;
  onEnable: () => void;
  onDone: () => void;
  onDisable: () => void;
}

interface PlansListProps {
  plans: PlanSummary[];
  actionPending: boolean;
  onApprove: (planId: string) => void;
  onCancel: (planId: string) => void;
  onResume: (planId: string) => void;
}

interface PlanItemActionsProps {
  plan: PlanSummary;
  actionPending: boolean;
  onApprove: (planId: string) => void;
  onCancel: (planId: string) => void;
  onResume: (planId: string) => void;
}

const CANCELLABLE_STATUSES = new Set(['COLLECTING', 'APPROVED', 'EXECUTING', 'READY', 'PARTIALLY_COMPLETED']);

function shortId(id: string): string {
  if (id.length <= 8) {
    return id;
  }
  return id.slice(0, 8);
}

function planStatusVariant(status: string): string {
  switch (status) {
  case 'COLLECTING':
    return 'secondary';
  case 'READY':
    return 'info';
  case 'APPROVED':
    return 'primary';
  case 'EXECUTING':
    return 'warning';
  case 'COMPLETED':
    return 'success';
  case 'PARTIALLY_COMPLETED':
    return 'warning';
  case 'CANCELLED':
    return 'dark';
  default:
    return 'secondary';
  }
}

function PlanControlHeader({ disabled, onRefresh }: PlanControlHeaderProps) {
  return (
    <div className="d-flex align-items-center justify-content-between mb-2">
      <div className="section-label mb-0">PLAN MODE</div>
      <button
        type="button"
        className="btn btn-link btn-sm p-0 text-decoration-none"
        onClick={onRefresh}
        disabled={disabled}
        aria-label="Refresh plan state"
      >
        Refresh
      </button>
    </div>
  );
}

function PlanControlBody({
  data,
  isLoading,
  isError,
  actionPending,
  onEnable,
  onDone,
  onDisable,
  onApprove,
  onCancel,
  onResume,
}: PlanControlBodyProps) {
  if (isLoading) {
    return (
      <div className="text-body-secondary small d-flex align-items-center gap-2">
        <Spinner animation="border" size="sm" />
        Loading plan state...
      </div>
    );
  }

  if (isError) {
    return <div className="text-danger small">Failed to load plan state.</div>;
  }

  if (data?.featureEnabled !== true) {
    return <div className="text-body-secondary small">Plan mode is disabled in runtime config.</div>;
  }

  return (
    <>
      <div className="d-flex align-items-center gap-2 mb-2">
        <Badge bg={data.planModeActive ? 'success' : 'secondary'}>
          {data.planModeActive ? 'ON' : 'OFF'}
        </Badge>
        {data.activePlanId != null && (
          <small className="text-body-secondary font-mono">active: {shortId(data.activePlanId)}</small>
        )}
      </div>

      <PlanModeActions
        planModeActive={data.planModeActive}
        actionPending={actionPending}
        onEnable={onEnable}
        onDone={onDone}
        onDisable={onDisable}
      />

      <PlansList
        plans={data.plans}
        actionPending={actionPending}
        onApprove={onApprove}
        onCancel={onCancel}
        onResume={onResume}
      />
    </>
  );
}

function PlanModeActions({
  planModeActive,
  actionPending,
  onEnable,
  onDone,
  onDisable,
}: PlanModeActionsProps) {
  if (!planModeActive) {
    return (
      <div className="d-flex flex-wrap gap-2 mb-3">
        <Button size="sm" variant="primary" disabled={actionPending} onClick={onEnable}>
          Plan ON
        </Button>
      </div>
    );
  }

  return (
    <div className="d-flex flex-wrap gap-2 mb-3">
      <Button size="sm" variant="success" disabled={actionPending} onClick={onDone}>
        Plan done
      </Button>
      <Button size="sm" variant="outline-secondary" disabled={actionPending} onClick={onDisable}>
        Plan OFF
      </Button>
    </div>
  );
}

function PlansList({ plans, actionPending, onApprove, onCancel, onResume }: PlansListProps) {
  if (plans.length === 0) {
    return (
      <div className="plan-control-list">
        <small className="text-body-secondary">No plans yet.</small>
      </div>
    );
  }

  return (
    <div className="plan-control-list">
      {plans.slice(0, 6).map((plan) => (
        <div key={plan.id} className={`plan-control-item ${plan.active ? 'plan-control-item--active' : ''}`}>
          <div className="d-flex align-items-start justify-content-between gap-2">
            <div className="min-w-0">
              <div className="plan-control-title text-truncate" title={plan.title ?? plan.id}>
                {plan.title?.trim() ?? `Plan ${shortId(plan.id)}`}
              </div>
              <div className="text-body-secondary small font-mono">{shortId(plan.id)}</div>
            </div>
            <Badge bg={planStatusVariant(plan.status)}>{plan.status}</Badge>
          </div>

          <div className="text-body-secondary small mt-1">
            {plan.completedStepCount}/{plan.stepCount} steps
            {plan.failedStepCount > 0 ? `, failed: ${plan.failedStepCount}` : ''}
          </div>

          <PlanItemActions
            plan={plan}
            actionPending={actionPending}
            onApprove={onApprove}
            onCancel={onCancel}
            onResume={onResume}
          />
        </div>
      ))}
    </div>
  );
}

function PlanItemActions({
  plan,
  actionPending,
  onApprove,
  onCancel,
  onResume,
}: PlanItemActionsProps) {
  const canApprove = plan.status === 'READY';
  const canResume = plan.status === 'PARTIALLY_COMPLETED';
  const canCancel = CANCELLABLE_STATUSES.has(plan.status);

  if (!canApprove && !canResume && !canCancel) {
    return null;
  }

  return (
    <div className="d-flex flex-wrap gap-2 mt-2">
      {canApprove && (
        <Button size="sm" variant="primary" disabled={actionPending} onClick={() => onApprove(plan.id)}>
          Approve
        </Button>
      )}

      {canResume && (
        <Button size="sm" variant="warning" disabled={actionPending} onClick={() => onResume(plan.id)}>
          Resume
        </Button>
      )}

      {canCancel && (
        <Button size="sm" variant="outline-danger" disabled={actionPending} onClick={() => onCancel(plan.id)}>
          Cancel
        </Button>
      )}
    </div>
  );
}

export default function PlanControlPanel({ chatSessionId }: Props) {
  const { data, isLoading, isError, refetch } = usePlanControlState(true);

  const enableMutation = useEnablePlanMode();
  const disableMutation = useDisablePlanMode();
  const doneMutation = useDonePlanMode();
  const approveMutation = useApprovePlan();
  const cancelMutation = useCancelPlan();
  const resumeMutation = useResumePlan();

  const actionPending = usePlanActionsPending([
    enableMutation,
    disableMutation,
    doneMutation,
    approveMutation,
    cancelMutation,
    resumeMutation,
  ]);

  return (
    <div className="context-section plan-control-section">
      <PlanControlHeader
        disabled={isLoading || actionPending}
        onRefresh={() => {
          void refetch();
        }}
      />

      <PlanControlBody
        data={data}
        isLoading={isLoading}
        isError={isError}
        actionPending={actionPending}
        onEnable={() => {
          enableMutation.mutate({ chatId: chatSessionId });
        }}
        onDone={() => {
          doneMutation.mutate();
        }}
        onDisable={() => {
          disableMutation.mutate();
        }}
        onApprove={(planId) => {
          approveMutation.mutate(planId);
        }}
        onCancel={(planId) => {
          cancelMutation.mutate(planId);
        }}
        onResume={(planId) => {
          resumeMutation.mutate(planId);
        }}
      />
    </div>
  );
}
