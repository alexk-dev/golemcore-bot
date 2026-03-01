import type { ReactElement } from 'react';
import { Badge } from 'react-bootstrap';

interface SchedulerStatusHeaderProps {
  featureEnabled: boolean;
  autoModeEnabled: boolean;
}

export function SchedulerStatusHeader({
  featureEnabled,
  autoModeEnabled,
}: SchedulerStatusHeaderProps): ReactElement {
  return (
    <div className="section-header d-flex align-items-center justify-content-between">
      <div>
        <h4 className="mb-1">Scheduler</h4>
        <p className="mb-0 text-body-secondary">Manage goal/task schedules with the same options as Telegram wizard.</p>
      </div>
      <div className="d-flex align-items-center gap-2">
        <Badge bg={featureEnabled ? 'success' : 'secondary'}>
          Feature: {featureEnabled ? 'On' : 'Off'}
        </Badge>
        <Badge bg={autoModeEnabled ? 'primary' : 'secondary'}>
          Auto mode: {autoModeEnabled ? 'On' : 'Off'}
        </Badge>
      </div>
    </div>
  );
}
