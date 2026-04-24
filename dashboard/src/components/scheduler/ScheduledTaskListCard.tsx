import { type ReactElement, Fragment, useState } from 'react';
import { FiChevronDown, FiChevronRight } from 'react-icons/fi';
import { Badge, Button, Card, Table } from '../ui/tailwind-components';
import type { SchedulerSchedule, SchedulerScheduledTask } from '../../api/scheduler';
import { getScheduledTaskAnchorId } from './automationLinks';
import { formatLimit, formatNextExecution } from './schedulerFormUtils';

interface ScheduledTaskListCardProps {
  scheduledTasks: SchedulerScheduledTask[];
  schedules: SchedulerSchedule[];
  busy: boolean;
  runningTaskId: string | null;
  onCreate: () => void;
  onRunNow: (scheduledTaskId: string) => void;
  onSchedule: (scheduledTaskId: string) => void;
  onEdit: (scheduledTask: SchedulerScheduledTask) => void;
  onDelete: (scheduledTaskId: string) => void;
  onViewLogs: (schedule: SchedulerSchedule) => void;
  onEditSchedule: (schedule: SchedulerSchedule) => void;
  onDeleteSchedule: (scheduleId: string) => void;
}

function renderTextBlock(
  label: string,
  value: string | null,
): ReactElement | null {
  if (value == null || value.length === 0) {
    return null;
  }

  return (
    <div className="small text-body-secondary">
      <strong>{label}:</strong> {value}
    </div>
  );
}

function renderModeSpecificFields(task: SchedulerScheduledTask): ReactElement | null {
  if (task.executionMode === 'SHELL_COMMAND') {
    return (
      <>
        {renderTextBlock('Command', task.shellCommand)}
        {renderTextBlock('Working directory', task.shellWorkingDirectory)}
      </>
    );
  }

  return (
    <>
      {renderTextBlock('Prompt', task.prompt)}
      {renderTextBlock('Reflection tier', task.reflectionModelTier)}
      {task.reflectionTierPriority && (
        <div className="small text-body-secondary">
          <strong>Reflection priority:</strong> Enabled
        </div>
      )}
    </>
  );
}

function resolveTaskSchedules(
  schedules: SchedulerSchedule[],
  scheduledTaskId: string,
): SchedulerSchedule[] {
  return schedules
    .filter((schedule) => schedule.type === 'SCHEDULED_TASK' && schedule.targetId === scheduledTaskId)
    .sort((left, right) => {
      const leftValue = left.nextExecutionAt ?? '9999-12-31T00:00:00Z';
      const rightValue = right.nextExecutionAt ?? '9999-12-31T00:00:00Z';
      return leftValue.localeCompare(rightValue);
    });
}

function resolveUnlinkedSchedules(
  schedules: SchedulerSchedule[],
  scheduledTasks: SchedulerScheduledTask[],
): SchedulerSchedule[] {
  const scheduledTaskIds = new Set(scheduledTasks.map((task) => task.id));
  return schedules
    .filter((schedule) => (
      schedule.type !== 'SCHEDULED_TASK' || !scheduledTaskIds.has(schedule.targetId)
    ))
    .sort((left, right) => {
      const leftValue = left.nextExecutionAt ?? '9999-12-31T00:00:00Z';
      const rightValue = right.nextExecutionAt ?? '9999-12-31T00:00:00Z';
      return leftValue.localeCompare(rightValue);
    });
}

function resolveStatusVariant(enabled: boolean): 'success' | 'secondary' {
  return enabled ? 'success' : 'secondary';
}

function resolveScheduleTarget(schedule: SchedulerSchedule): string {
  return schedule.targetLabel.length > 0 ? schedule.targetLabel : schedule.targetId;
}

interface ScheduleActionButtonsProps {
  schedule: SchedulerSchedule;
  busy: boolean;
  onViewLogs: (schedule: SchedulerSchedule) => void;
  onEditSchedule: (schedule: SchedulerSchedule) => void;
  onDeleteSchedule: (scheduleId: string) => void;
}

function ScheduleActionButtons({
  schedule,
  busy,
  onViewLogs,
  onEditSchedule,
  onDeleteSchedule,
}: ScheduleActionButtonsProps): ReactElement {
  return (
    <div className="d-flex gap-2 flex-wrap">
      <Button type="button" size="sm" variant="secondary" disabled={busy} onClick={() => onViewLogs(schedule)}>
        Logs
      </Button>
      <Button type="button" size="sm" variant="secondary" disabled={busy} onClick={() => onEditSchedule(schedule)}>
        Edit
      </Button>
      <Button type="button" size="sm" variant="danger" disabled={busy} onClick={() => onDeleteSchedule(schedule.id)}>
        Delete
      </Button>
    </div>
  );
}

function renderScheduleSummary(
  taskSchedules: SchedulerSchedule[],
): ReactElement {
  if (taskSchedules.length === 0) {
    return <div className="small text-body-secondary">No schedule attached.</div>;
  }

  const nextSchedule = taskSchedules[0];
  return (
    <div className="small text-body-secondary">
      <div>
        <strong>Schedules:</strong> {taskSchedules.length}
      </div>
      <div>
        <strong>Next:</strong> {formatNextExecution(nextSchedule.nextExecutionAt)}
      </div>
    </div>
  );
}

function ExpandedSchedulesRow({
  taskSchedules,
  busy,
  onViewLogs,
  onEditSchedule,
  onDeleteSchedule,
}: {
  taskSchedules: SchedulerSchedule[];
  busy: boolean;
  onViewLogs: (schedule: SchedulerSchedule) => void;
  onEditSchedule: (schedule: SchedulerSchedule) => void;
  onDeleteSchedule: (scheduleId: string) => void;
}): ReactElement {
  return (
    <div className="d-flex flex-column gap-2">
      {taskSchedules.map((schedule) => (
        <div
          key={schedule.id}
          className="rounded-lg border border-border/80 p-2"
        >
          <div className="d-flex align-items-center gap-2 flex-wrap mb-1">
            <Badge bg={resolveStatusVariant(schedule.enabled)}>
              {schedule.enabled ? 'Enabled' : 'Disabled'}
            </Badge>
            {schedule.clearContextBeforeRun && (
              <Badge bg="secondary">Clears context</Badge>
            )}
            <span className="small text-body-secondary">
              schedule <code>{schedule.id}</code>
            </span>
          </div>
          <div className="small">
            <code>{schedule.cronExpression}</code>
          </div>
          <div className="small text-body-secondary mb-2">
            Next: {formatNextExecution(schedule.nextExecutionAt)} · Runs: {schedule.executionCount} / {formatLimit(schedule.maxExecutions)}
          </div>
          <ScheduleActionButtons
            schedule={schedule}
            busy={busy}
            onViewLogs={onViewLogs}
            onEditSchedule={onEditSchedule}
            onDeleteSchedule={onDeleteSchedule}
          />
        </div>
      ))}
    </div>
  );
}

function UnlinkedSchedulesSection({
  schedules,
  busy,
  onViewLogs,
  onEditSchedule,
  onDeleteSchedule,
}: {
  schedules: SchedulerSchedule[];
  busy: boolean;
  onViewLogs: (schedule: SchedulerSchedule) => void;
  onEditSchedule: (schedule: SchedulerSchedule) => void;
  onDeleteSchedule: (scheduleId: string) => void;
}): ReactElement | null {
  if (schedules.length === 0) {
    return null;
  }

  return (
    <div className="border-t border-border/80 p-3">
      <div className="fw-semibold mb-2">Unlinked schedules</div>
      <div className="d-flex flex-column gap-2">
        {schedules.map((schedule) => (
          <div
            key={schedule.id}
            className="rounded-lg border border-border/80 p-2"
          >
            <div className="d-flex align-items-center gap-2 flex-wrap mb-1">
              <Badge bg={resolveStatusVariant(schedule.enabled)}>
                {schedule.enabled ? 'Enabled' : 'Disabled'}
              </Badge>
              <Badge bg="secondary">{schedule.type}</Badge>
              <span className="small text-body-secondary">
                schedule <code>{schedule.id}</code>
              </span>
            </div>
            <div className="small text-body-secondary">
              Target: <strong>{resolveScheduleTarget(schedule)}</strong> <code>{schedule.targetId}</code>
            </div>
            <div className="small">
              <code>{schedule.cronExpression}</code>
            </div>
            <div className="small text-body-secondary mb-2">
              Next: {formatNextExecution(schedule.nextExecutionAt)} · Runs: {schedule.executionCount} / {formatLimit(schedule.maxExecutions)}
            </div>
            <ScheduleActionButtons
              schedule={schedule}
              busy={busy}
              onViewLogs={onViewLogs}
              onEditSchedule={onEditSchedule}
              onDeleteSchedule={onDeleteSchedule}
            />
          </div>
        ))}
      </div>
    </div>
  );
}

export function ScheduledTaskListCard({
  scheduledTasks,
  schedules,
  busy,
  runningTaskId,
  onCreate,
  onRunNow,
  onSchedule,
  onEdit,
  onDelete,
  onViewLogs,
  onEditSchedule,
  onDeleteSchedule,
}: ScheduledTaskListCardProps): ReactElement {
  const [expandedTaskIds, setExpandedTaskIds] = useState<string[]>([]);
  const unlinkedSchedules = resolveUnlinkedSchedules(schedules, scheduledTasks);

  const toggleExpanded = (scheduledTaskId: string): void => {
    setExpandedTaskIds((current) => (
      current.includes(scheduledTaskId)
        ? current.filter((id) => id !== scheduledTaskId)
        : [...current, scheduledTaskId]
    ));
  };

  return (
    <Card>
      <Card.Header className="d-flex align-items-center justify-content-between gap-3">
        <div className="fw-semibold">Scheduled tasks ({scheduledTasks.length})</div>
        <Button type="button" size="sm" variant="primary" onClick={onCreate}>
          New task
        </Button>
      </Card.Header>
      <Card.Body className="p-0">
        {scheduledTasks.length === 0 && unlinkedSchedules.length === 0 && (
          <div className="p-3 text-body-secondary">
            No scheduled tasks yet.
          </div>
        )}

        {scheduledTasks.length > 0 && (
          <Table responsive hover className="mb-0 align-top">
            <thead>
              <tr>
                <th>Task</th>
                <th>Schedules</th>
                <th className="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              {scheduledTasks.map((task) => {
                const taskSchedules = resolveTaskSchedules(schedules, task.id);
                const taskBusy = busy || runningTaskId === task.id;
                const isExpanded = expandedTaskIds.includes(task.id);
                return (
                  <Fragment key={task.id}>
                    <tr id={getScheduledTaskAnchorId(task.id)}>
                      <td className="align-top">
                        <div className="d-flex align-items-center gap-2 flex-wrap mb-1">
                          <strong>{task.title}</strong>
                          <Badge bg={task.executionMode === 'SHELL_COMMAND' ? 'warning' : 'primary'}>
                            {task.executionMode === 'SHELL_COMMAND' ? 'Shell' : 'Agent'}
                          </Badge>
                          {task.legacySourceType != null && (
                            <Badge bg="secondary">
                              Migrated {task.legacySourceType.toLowerCase()}
                            </Badge>
                          )}
                        </div>

                        <div className="small text-body-secondary mb-2">
                          scheduled task <code>{task.id}</code>
                        </div>

                        {renderTextBlock('Details', task.description)}
                        {renderModeSpecificFields(task)}
                      </td>
                      <td className="align-top">
                        <div className="d-flex align-items-start justify-content-between gap-3">
                          {renderScheduleSummary(taskSchedules)}
                          {taskSchedules.length > 0 && (
                            <Button
                              type="button"
                              size="sm"
                              variant="secondary"
                              disabled={busy}
                              onClick={() => toggleExpanded(task.id)}
                            >
                              {isExpanded ? <FiChevronDown size={14} /> : <FiChevronRight size={14} />}
                              <span className="ms-1">{isExpanded ? 'Hide' : 'Show'}</span>
                            </Button>
                          )}
                        </div>
                      </td>
                      <td className="align-top text-end">
                        <div className="d-flex justify-content-end gap-2 flex-wrap">
                          <Button
                            type="button"
                            size="sm"
                            variant="primary"
                            disabled={taskBusy}
                            onClick={() => onRunNow(task.id)}
                          >
                            {runningTaskId === task.id ? 'Running...' : 'Run now'}
                          </Button>
                          <Button
                            type="button"
                            size="sm"
                            variant="secondary"
                            disabled={taskBusy}
                            onClick={() => onSchedule(task.id)}
                          >
                            Add schedule
                          </Button>
                          <Button
                            type="button"
                            size="sm"
                            variant="secondary"
                            disabled={taskBusy}
                            onClick={() => onEdit(task)}
                          >
                            Edit task
                          </Button>
                          <Button
                            type="button"
                            size="sm"
                            variant="danger"
                            disabled={taskBusy}
                            onClick={() => onDelete(task.id)}
                          >
                            Delete task
                          </Button>
                        </div>
                      </td>
                    </tr>
                    {isExpanded && taskSchedules.length > 0 && (
                      <tr>
                        <td colSpan={3} className="bg-muted/10">
                          <div className="py-2">
                            <ExpandedSchedulesRow
                              taskSchedules={taskSchedules}
                              busy={busy}
                              onViewLogs={onViewLogs}
                              onEditSchedule={onEditSchedule}
                              onDeleteSchedule={onDeleteSchedule}
                            />
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </Table>
        )}

        <UnlinkedSchedulesSection
          schedules={unlinkedSchedules}
          busy={busy}
          onViewLogs={onViewLogs}
          onEditSchedule={onEditSchedule}
          onDeleteSchedule={onDeleteSchedule}
        />
      </Card.Body>
    </Card>
  );
}
