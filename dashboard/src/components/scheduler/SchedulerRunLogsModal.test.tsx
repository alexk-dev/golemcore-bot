/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';
import { SchedulerRunLogsModal } from './SchedulerRunLogsModal';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

describe('SchedulerRunLogsModal', () => {
  it('renders scheduled task identity when a run is not tied to goal/task context', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    const run = {
      runId: 'run-1',
      sessionId: 'session-auto',
      channelType: 'auto',
      conversationKey: 'auto',
      transportChatId: null,
      scheduleId: 'sched-1',
      scheduleTargetType: 'SCHEDULED_TASK',
      scheduleTargetId: 'scheduled-task-1',
      scheduleTargetLabel: 'Nightly cleanup',
      scheduledTaskId: 'scheduled-task-1',
      scheduledTaskLabel: 'Nightly cleanup',
      goalId: null,
      goalLabel: null,
      taskId: null,
      taskLabel: null,
      status: 'COMPLETED',
      messageCount: 1,
      startedAt: '2026-04-23T10:00:00Z',
      lastActivityAt: '2026-04-23T10:01:00Z',
    };

    act(() => {
      root.render(
        <SchedulerRunLogsModal
          show
          scheduleLabel="Nightly cleanup"
          scheduleId="sched-1"
          runs={[run]}
          runsLoading={false}
          selectedRunId="run-1"
          runDetail={{
            ...run,
            messages: [],
          }}
          runDetailLoading={false}
          resolveGoalHref={() => null}
          resolveTaskHref={() => null}
          resolveScheduledTaskHref={() => null}
          onHide={vi.fn()}
          onSelectRun={vi.fn()}
        />,
      );
    });

    expect(document.body.textContent ?? '').toContain('Scheduled task: Nightly cleanup');
    expect(document.body.textContent ?? '').toContain('scheduled-task-1');

    act(() => {
      root.unmount();
    });
    container.remove();
  });
});
