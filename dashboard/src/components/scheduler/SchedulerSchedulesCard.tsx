import type { ReactElement } from 'react';
import { Button, Card, Table } from 'react-bootstrap';
import type { SchedulerSchedule } from '../../api/scheduler';
import { formatLimit, formatNextExecution } from './schedulerFormUtils';

interface SchedulerSchedulesCardProps {
  schedules: SchedulerSchedule[];
  busy: boolean;
  onDelete: (scheduleId: string) => void;
}

export function SchedulerSchedulesCard({
  schedules,
  busy,
  onDelete,
}: SchedulerSchedulesCardProps): ReactElement {
  return (
    <Card className="h-100">
      <Card.Header className="fw-semibold">Active schedules ({schedules.length})</Card.Header>
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
                <th className="text-end">Action</th>
              </tr>
            </thead>
            <tbody>
              {schedules.map((schedule) => (
                <tr key={schedule.id}>
                  <td>
                    <div className="fw-semibold">{schedule.targetLabel}</div>
                    <div className="small text-body-secondary">{schedule.type} · {schedule.id}</div>
                  </td>
                  <td><code>{schedule.cronExpression}</code></td>
                  <td>{schedule.executionCount} / {formatLimit(schedule.maxExecutions)}</td>
                  <td>{formatNextExecution(schedule.nextExecutionAt)}</td>
                  <td className="text-end">
                    <Button
                      type="button"
                      size="sm"
                      variant="danger"
                      disabled={busy}
                      onClick={() => onDelete(schedule.id)}
                    >
                      Delete
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card.Body>
    </Card>
  );
}
