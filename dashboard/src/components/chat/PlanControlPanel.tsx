import { Badge, Button, Spinner } from '../ui/tailwind-components';
import type { PlanControlState } from '../../api/plans';
import {
  useDisablePlanMode,
  useDonePlanMode,
  useEnablePlanMode,
  usePlanActionsPending,
  usePlanControlState,
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

  const planModeActive = data?.planModeActive === true;

  return (
    <>
      <div className="d-flex align-items-center gap-2 mb-2">
        <Badge bg={planModeActive ? 'success' : 'secondary'}>
          {planModeActive ? 'ON' : 'OFF'}
        </Badge>
      </div>

      {!planModeActive ? (
        <div className="d-flex flex-column gap-2">
          <Button
            type="button"
            size="sm"
            variant="primary"
            disabled={actionPending}
            onClick={onEnable}
          >
            Plan ON
          </Button>
        </div>
      ) : (
        <div className="d-flex flex-wrap gap-2">
          <Button type="button" size="sm" variant="success" disabled={actionPending} onClick={onDone}>
            Plan done
          </Button>
          <Button type="button" size="sm" variant="secondary" disabled={actionPending} onClick={onDisable}>
            Plan OFF
          </Button>
        </div>
      )}
    </>
  );
}

export default function PlanControlPanel({ chatSessionId }: Props) {
  const { data, isLoading, isError, refetch } = usePlanControlState(chatSessionId, true);

  const enableMutation = useEnablePlanMode();
  const disableMutation = useDisablePlanMode();
  const doneMutation = useDonePlanMode();

  const actionPending = usePlanActionsPending([
    enableMutation,
    disableMutation,
    doneMutation,
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
          enableMutation.mutate({ sessionId: chatSessionId });
        }}
        onDone={() => {
          doneMutation.mutate(chatSessionId);
        }}
        onDisable={() => {
          disableMutation.mutate(chatSessionId);
        }}
      />
    </div>
  );
}
