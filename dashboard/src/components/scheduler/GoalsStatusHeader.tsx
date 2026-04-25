import type { ReactElement } from 'react';
import { Badge } from '../ui/tailwind-components';

interface GoalsStatusHeaderProps {
  featureEnabled: boolean;
  autoModeEnabled: boolean;
}

export function GoalsStatusHeader({
  featureEnabled,
  autoModeEnabled,
}: GoalsStatusHeaderProps): ReactElement {
  return (
    <div className="section-header d-flex align-items-center justify-content-between">
      <div>
        <h4 className="mb-1">Goals & Tasks</h4>
        <p className="mb-0 text-body-secondary">
          Create and manage goals, standalone tasks, and detailed execution prompts.
          Scheduling now lives on the Scheduler page via Scheduled Tasks.
        </p>
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
