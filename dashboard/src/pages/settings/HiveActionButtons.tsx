import type { ReactElement } from 'react';
import { Button } from 'react-bootstrap';

import type { HiveStatusResponse } from '../../api/hive';

interface HiveActionButtonsProps {
  isManaged: boolean;
  isBusy: boolean;
  joinCode: string;
  status: HiveStatusResponse | undefined;
  joinPending: boolean;
  reconnectPending: boolean;
  leavePending: boolean;
  onJoin: () => Promise<void>;
  onReconnect: () => Promise<void>;
  onLeave: () => Promise<void>;
}

function canJoinHive(status: HiveStatusResponse | undefined, isManaged: boolean, joinCode: string): boolean {
  if (status?.sessionPresent === true) {
    return false;
  }
  return isManaged ? status?.managedJoinCodeAvailable === true : joinCode.trim().length > 0;
}

function canReconnectHive(status: HiveStatusResponse | undefined, isManaged: boolean): boolean {
  return status?.sessionPresent === true || (isManaged && status?.managedJoinCodeAvailable === true);
}

export function HiveActionButtons({
  isManaged,
  isBusy,
  joinCode,
  status,
  joinPending,
  reconnectPending,
  leavePending,
  onJoin,
  onReconnect,
  onLeave,
}: HiveActionButtonsProps): ReactElement {
  return (
    <div className="d-flex flex-wrap gap-2 mb-4">
      <Button
        type="button"
        variant="success"
        size="sm"
        onClick={() => { void onJoin(); }}
        disabled={!canJoinHive(status, isManaged, joinCode) || isBusy}
      >
        {joinPending ? 'Joining...' : 'Join'}
      </Button>
      <Button
        type="button"
        variant="primary"
        size="sm"
        onClick={() => { void onReconnect(); }}
        disabled={!canReconnectHive(status, isManaged) || isBusy}
      >
        {reconnectPending ? 'Reconnecting...' : 'Reconnect'}
      </Button>
      <Button
        type="button"
        variant="danger"
        size="sm"
        onClick={() => { void onLeave(); }}
        disabled={status?.sessionPresent !== true || isBusy}
      >
        {leavePending ? 'Clearing...' : 'Leave'}
      </Button>
    </div>
  );
}
