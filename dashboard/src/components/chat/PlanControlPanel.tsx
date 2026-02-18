import { Badge, Button, Spinner } from 'react-bootstrap';
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

  const featureEnabled = data?.featureEnabled === true;
  const planModeActive = data?.planModeActive === true;
  const plans = data?.plans ?? [];

  return (
    <div className="context-section plan-control-section">
      <div className="d-flex align-items-center justify-content-between mb-2">
        <div className="section-label mb-0">PLAN MODE</div>
        <button
          type="button"
          className="btn btn-link btn-sm p-0 text-decoration-none"
          onClick={() => void refetch()}
          disabled={isLoading || actionPending}
          aria-label="Refresh plan state"
        >
          Refresh
        </button>
      </div>

      {isLoading && (
        <div className="text-body-secondary small d-flex align-items-center gap-2">
          <Spinner animation="border" size="sm" />
          Loading plan state...
        </div>
      )}

      {!isLoading && isError && (
        <div className="text-danger small">Failed to load plan state.</div>
      )}

      {!isLoading && !isError && !featureEnabled && (
        <div className="text-body-secondary small">Plan mode is disabled in runtime config.</div>
      )}

      {!isLoading && !isError && featureEnabled && (
        <>
          <div className="d-flex align-items-center gap-2 mb-2">
            <Badge bg={planModeActive ? 'success' : 'secondary'}>
              {planModeActive ? 'ON' : 'OFF'}
            </Badge>
            {data?.activePlanId != null && (
              <small className="text-body-secondary font-mono">active: {shortId(data.activePlanId)}</small>
            )}
          </div>

          <div className="d-flex flex-wrap gap-2 mb-3">
            {!planModeActive ? (
              <Button
                size="sm"
                variant="primary"
                disabled={actionPending}
                onClick={() => enableMutation.mutate({ chatId: chatSessionId })}
              >
                Plan ON
              </Button>
            ) : (
              <>
                <Button size="sm" variant="success" disabled={actionPending} onClick={() => doneMutation.mutate()}>
                  Plan done
                </Button>
                <Button size="sm" variant="outline-secondary" disabled={actionPending} onClick={() => disableMutation.mutate()}>
                  Plan OFF
                </Button>
              </>
            )}
          </div>

          <div className="plan-control-list">
            {plans.length === 0 ? (
              <small className="text-body-secondary">No plans yet.</small>
            ) : (
              plans.slice(0, 6).map((plan) => (
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

                  <div className="d-flex flex-wrap gap-2 mt-2">
                    {plan.status === 'READY' && (
                      <>
                        <Button
                          size="sm"
                          variant="primary"
                          disabled={actionPending}
                          onClick={() => approveMutation.mutate(plan.id)}
                        >
                          Approve
                        </Button>
                        <Button
                          size="sm"
                          variant="outline-danger"
                          disabled={actionPending}
                          onClick={() => cancelMutation.mutate(plan.id)}
                        >
                          Cancel
                        </Button>
                      </>
                    )}

                    {plan.status === 'PARTIALLY_COMPLETED' && (
                      <>
                        <Button
                          size="sm"
                          variant="warning"
                          disabled={actionPending}
                          onClick={() => resumeMutation.mutate(plan.id)}
                        >
                          Resume
                        </Button>
                        <Button
                          size="sm"
                          variant="outline-danger"
                          disabled={actionPending}
                          onClick={() => cancelMutation.mutate(plan.id)}
                        >
                          Cancel
                        </Button>
                      </>
                    )}

                    {(plan.status === 'COLLECTING' || plan.status === 'APPROVED' || plan.status === 'EXECUTING') && (
                      <Button
                        size="sm"
                        variant="outline-danger"
                        disabled={actionPending}
                        onClick={() => cancelMutation.mutate(plan.id)}
                      >
                        Cancel
                      </Button>
                    )}
                  </div>
                </div>
              ))
            )}
          </div>
        </>
      )}
    </div>
  );
}
