import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { SchedulerSchedule, SchedulerScheduledTask } from '../../api/scheduler';
import { ScheduledTaskListCard } from './ScheduledTaskListCard';

const scheduledTask: SchedulerScheduledTask = {
  id: 'scheduled-task-1',
  title: 'Nightly cleanup',
  description: null,
  prompt: 'Clean old files',
  executionMode: 'AGENT_PROMPT',
  shellCommand: null,
  shellWorkingDirectory: null,
  reflectionModelTier: null,
  reflectionTierPriority: false,
  legacySourceType: null,
  legacySourceId: null,
};

function schedule(overrides: Partial<SchedulerSchedule>): SchedulerSchedule {
  return {
    id: 'sched-1',
    type: 'SCHEDULED_TASK',
    targetId: 'scheduled-task-1',
    targetLabel: 'Nightly cleanup',
    cronExpression: '0 0 9 * * *',
    enabled: true,
    clearContextBeforeRun: false,
    report: null,
    maxExecutions: -1,
    executionCount: 0,
    createdAt: '2026-04-23T09:00:00Z',
    updatedAt: '2026-04-23T09:00:00Z',
    lastExecutedAt: null,
    nextExecutionAt: '2026-04-24T09:00:00Z',
    ...overrides,
  };
}

describe('ScheduledTaskListCard', () => {
  it('renders schedules that are not linked to a visible scheduled task', () => {
    const html = renderToStaticMarkup(
      <ScheduledTaskListCard
        scheduledTasks={[scheduledTask]}
        schedules={[
          schedule({ id: 'sched-linked' }),
          schedule({
            id: 'sched-missing-target',
            targetId: 'deleted-scheduled-task',
            targetLabel: 'deleted-scheduled-task',
          }),
          schedule({
            id: 'sched-legacy-goal',
            type: 'GOAL',
            targetId: 'legacy-goal-1',
            targetLabel: 'legacy-goal-1',
            enabled: false,
            nextExecutionAt: null,
          }),
        ]}
        busy={false}
        runningTaskId={null}
        onCreate={vi.fn()}
        onRunNow={vi.fn()}
        onSchedule={vi.fn()}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onViewLogs={vi.fn()}
        onEditSchedule={vi.fn()}
        onDeleteSchedule={vi.fn()}
      />,
    );

    expect(html).toContain('Unlinked schedules');
    expect(html).toContain('sched-missing-target');
    expect(html).toContain('sched-legacy-goal');
    expect(html).toContain('deleted-scheduled-task');
    expect(html).toContain('legacy-goal-1');
  });

  it('does not show an empty task placeholder when unlinked schedules exist', () => {
    const html = renderToStaticMarkup(
      <ScheduledTaskListCard
        scheduledTasks={[]}
        schedules={[
          schedule({
            id: 'sched-orphan',
            targetId: 'deleted-scheduled-task',
            targetLabel: 'deleted-scheduled-task',
          }),
        ]}
        busy={false}
        runningTaskId={null}
        onCreate={vi.fn()}
        onRunNow={vi.fn()}
        onSchedule={vi.fn()}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onViewLogs={vi.fn()}
        onEditSchedule={vi.fn()}
        onDeleteSchedule={vi.fn()}
      />,
    );

    expect(html).toContain('Unlinked schedules');
    expect(html).toContain('sched-orphan');
    expect(html).not.toContain('No scheduled tasks yet.');
  });
});
