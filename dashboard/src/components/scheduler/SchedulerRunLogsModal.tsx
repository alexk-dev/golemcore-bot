import type { ReactElement } from 'react';
import { Badge, ListGroup, Modal, Spinner } from 'react-bootstrap';
import type { SchedulerRunDetail, SchedulerRunSummary } from '../../api/scheduler';
import { formatTimestamp } from './schedulerFormUtils';

interface SchedulerRunLogsModalProps {
  show: boolean;
  scheduleLabel: string | null;
  scheduleId: string | null;
  runs: SchedulerRunSummary[];
  runsLoading: boolean;
  selectedRunId: string | null;
  runDetail: SchedulerRunDetail | undefined;
  runDetailLoading: boolean;
  onHide: () => void;
  onSelectRun: (runId: string) => void;
}

function resolveStatusVariant(status: string): string {
  if (status === 'COMPLETED') {
    return 'success';
  }
  if (status === 'TOOL_OUTPUT') {
    return 'warning';
  }
  return 'secondary';
}

export function SchedulerRunLogsModal({
  show,
  scheduleLabel,
  scheduleId,
  runs,
  runsLoading,
  selectedRunId,
  runDetail,
  runDetailLoading,
  onHide,
  onSelectRun,
}: SchedulerRunLogsModalProps): ReactElement {
  return (
    <Modal show={show} onHide={onHide} size="xl">
      <Modal.Header closeButton>
        <Modal.Title>
          Scheduler logs
          {scheduleLabel != null && scheduleLabel.length > 0 ? ` · ${scheduleLabel}` : ''}
        </Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <div className="mb-3">
          {scheduleId != null && scheduleId.length > 0 && (
            <div className="small text-body-secondary">
              Schedule ID: <code>{scheduleId}</code>
            </div>
          )}
          <div className="small text-body-secondary">Runs are grouped by auto run id and sourced from persisted session messages.</div>
        </div>

        <div className="row g-3">
          <div className="col-lg-4">
            <div className="fw-semibold mb-2">Recent runs</div>
            {runsLoading ? (
              <div className="d-flex align-items-center gap-2 text-body-secondary">
                <Spinner size="sm" />
                <span>Loading runs...</span>
              </div>
            ) : runs.length === 0 ? (
              <div className="text-body-secondary small">No runs recorded yet.</div>
            ) : (
              <ListGroup>
                {runs.map((run) => (
                  <ListGroup.Item
                    key={run.runId}
                    action
                    active={run.runId === selectedRunId}
                    onClick={() => onSelectRun(run.runId)}
                  >
                    <div className="d-flex justify-content-between align-items-start gap-2">
                      <div>
                        <div className="fw-semibold">{formatTimestamp(run.startedAt)}</div>
                        <div className="small text-body-secondary">
                          run <code>{run.runId.slice(0, 8)}</code>
                        </div>
                      </div>
                      <Badge bg={resolveStatusVariant(run.status)}>{run.status}</Badge>
                    </div>
                    {run.taskLabel != null && run.taskLabel.length > 0 && (
                      <div className="small mt-2">
                        Task: {run.taskLabel}
                        {run.taskId != null && run.taskId.length > 0 && <> · <code>{run.taskId}</code></>}
                      </div>
                    )}
                    <div className="small text-body-secondary mt-1">
                      {run.messageCount} messages · session <code>{run.sessionId}</code>
                    </div>
                  </ListGroup.Item>
                ))}
              </ListGroup>
            )}
          </div>

          <div className="col-lg-8">
            <div className="fw-semibold mb-2">Transcript</div>
            {runDetailLoading ? (
              <div className="d-flex align-items-center gap-2 text-body-secondary">
                <Spinner size="sm" />
                <span>Loading transcript...</span>
              </div>
            ) : runDetail == null ? (
              <div className="text-body-secondary small">Select a run to inspect its transcript.</div>
            ) : (
              <>
                <div className="mb-3 small text-body-secondary">
                  <div>Conversation: <code>{runDetail.conversationKey}</code></div>
                  {runDetail.goalId != null && runDetail.goalId.length > 0 && (
                    <div>
                      Goal: {runDetail.goalLabel ?? runDetail.goalId} · <code>{runDetail.goalId}</code>
                    </div>
                  )}
                  {runDetail.taskId != null && runDetail.taskId.length > 0 && (
                    <div>
                      Task: {runDetail.taskLabel ?? runDetail.taskId} · <code>{runDetail.taskId}</code>
                    </div>
                  )}
                </div>

                <div className="d-flex flex-column gap-2">
                  {runDetail.messages.map((message) => (
                    <div
                      key={message.id}
                      className={`p-3 rounded ${message.role === 'user' ? 'bg-primary-subtle text-primary-emphasis' : 'bg-body-tertiary'}`}
                    >
                      <div className="d-flex justify-content-between gap-2 align-items-start">
                        <div className="fw-semibold text-uppercase small">{message.role}</div>
                        <div className="small text-body-secondary">{formatTimestamp(message.timestamp)}</div>
                      </div>
                      <div className="mt-2 sessions-message">{message.content}</div>
                      <div className="mt-2 small text-body-secondary">
                        {message.modelTier != null && message.modelTier.length > 0 && <>tier {message.modelTier}</>}
                        {message.model != null && message.model.length > 0 && <> · model {message.model}</>}
                      </div>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>
      </Modal.Body>
    </Modal>
  );
}
