import type { ReactElement } from 'react';

export function SchedulerStatusHeader(): ReactElement {
  return (
    <div className="section-header">
      <div>
        <h4 className="mb-1">Scheduler</h4>
        <p className="mb-0 text-body-secondary">
          Manage persistent scheduled tasks, attach schedules, and inspect autonomous run logs.
        </p>
      </div>
    </div>
  );
}
