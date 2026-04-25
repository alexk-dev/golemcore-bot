import type { ReactElement } from 'react';
import { Badge, Button, Card, Table } from '../ui/tailwind-components';
import { Link } from 'react-router-dom';
import type { SchedulerSchedule } from '../../api/scheduler';
import { formatLimit, formatNextExecution } from './schedulerFormUtils';

interface SchedulerSchedulesCardProps {
  schedules: SchedulerSchedule[];
  busy: boolean;
  canCreate: boolean;
  resolveTargetHref: (schedule: SchedulerSchedule) => string | null;
  onCreate: () => void;
  onDelete: (scheduleId: string) => void;
  onEdit: (schedule: SchedulerSchedule) => void;
  onViewLogs: (schedule: SchedulerSchedule) => void;
}

function resolveStatusVariant(enabled: boolean): 'success' | 'secondary' {
  return enabled ? 'success' : 'secondary';
}

function isEditableSchedule(schedule: SchedulerSchedule): boolean {
  return schedule.type === 'SCHEDULED_TASK';
}

export function SchedulerSchedulesCard({
  schedules,
  busy,
  canCreate,
  resolveTargetHref,
  onCreate,
  onDelete,
  onEdit,
  onViewLogs,
}: SchedulerSchedulesCardProps): ReactElement {
  return (
    <Card className="h-100">
      <Card.Header className="d-flex align-items-center justify-content-between gap-3">
        <div className="fw-semibold">Schedules ({schedules.length})</div>
        <Button
          type="button"
          size="sm"
          variant="primary"
          disabled={!canCreate}
          onClick={onCreate}
        >
          New schedule
        </Button>
      </Card.Header>
      <Card.Body className="p-0">
        {schedules.length === 0 ? (
          <div className="p-3 text-body-secondary">No schedules yet.</div>
        ) : (
          <Table responsive hover className="mb-0 align-middle">
            <thead>
              <tr>
                <th>Target</th>
                <th>Cron</th>
                <th>Runs</th>
                <th>Next</th>
                <th className="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              {schedules.map((schedule) => {
                const targetHref = resolveTargetHref(schedule);
                const canEdit = isEditableSchedule(schedule);
                return (
                  <tr key={schedule.id}>
                    <td>
                      <div className="d-flex align-items-center gap-2 flex-wrap">
                        {targetHref != null ? (
                          <Link to={targetHref} className="fw-semibold text-decoration-none">
                            {schedule.targetLabel}
                          </Link>
                        ) : (
                          <div className="fw-semibold">{schedule.targetLabel}</div>
                        )}
                        <Badge bg={resolveStatusVariant(schedule.enabled)}>
                          {schedule.enabled ? 'Enabled' : 'Disabled'}
                        </Badge>
                        {schedule.clearContextBeforeRun && (
                          <Badge bg="secondary">Clears context</Badge>
                        )}
                        {!canEdit && (
                          <Badge bg="warning">Legacy target</Badge>
                        )}
                      </div>
                      <div className="small text-body-secondary">
                        {schedule.type}
                      </div>
                      <div className="small text-body-secondary">
                        schedule <code>{schedule.id}</code>
                      </div>
                    </td>
                    <td><code>{schedule.cronExpression}</code></td>
                    <td>{schedule.executionCount} / {formatLimit(schedule.maxExecutions)}</td>
                    <td>{formatNextExecution(schedule.nextExecutionAt)}</td>
                    <td className="text-end">
                      <div className="d-flex justify-content-end gap-2">
                        <Button
                          type="button"
                          size="sm"
                          variant="secondary"
                          disabled={busy}
                          onClick={() => onViewLogs(schedule)}
                        >
                          Logs
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          variant="secondary"
                          disabled={busy || !canEdit}
                          onClick={() => onEdit(schedule)}
                        >
                          Edit
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          variant="danger"
                          disabled={busy}
                          onClick={() => onDelete(schedule.id)}
                        >
                          Delete
                        </Button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
        )}
      </Card.Body>
    </Card>
  );
}
