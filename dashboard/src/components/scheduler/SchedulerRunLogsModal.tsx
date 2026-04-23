import type { ReactElement } from 'react';
import { Badge, ListGroup, Modal, Spinner } from '../ui/tailwind-components';
import { Link } from 'react-router-dom';
import type { SchedulerRunDetail, SchedulerRunMessage, SchedulerRunSummary } from '../../api/scheduler';
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
  resolveGoalHref: (goalId: string) => string | null;
  resolveTaskHref: (taskId: string) => string | null;
  resolveScheduledTaskHref: (scheduledTaskId: string) => string | null;
  onHide: () => void;
  onSelectRun: (runId: string) => void;
}

interface TargetLinkProps {
  label: string;
  id: string;
  href: string | null;
  onNavigate: () => void;
}

interface RunsListProps {
  runs: SchedulerRunSummary[];
  runsLoading: boolean;
  selectedRunId: string | null;
  onSelectRun: (runId: string) => void;
}

interface RunTranscriptProps {
  runDetail: SchedulerRunDetail | undefined;
  runDetailLoading: boolean;
  resolveGoalHref: (goalId: string) => string | null;
  resolveTaskHref: (taskId: string) => string | null;
  resolveScheduledTaskHref: (scheduledTaskId: string) => string | null;
  onNavigate: () => void;
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

function TargetLink({ label, id, href, onNavigate }: TargetLinkProps): ReactElement {
  return (
    <div>
      {href != null ? (
        <Link to={href} onClick={onNavigate}>
          {label}
        </Link>
      ) : (
        <>{label}</>
      )}{' '}
      · <code>{id}</code>
    </div>
  );
}

function shouldShowMessageMetadata(message: SchedulerRunMessage): boolean {
  const supportsMetadata = message.role === 'assistant' || message.role === 'tool';
  if (!supportsMetadata) {
    return false;
  }

  return (message.modelTier != null && message.modelTier.length > 0)
    || (message.skill != null && message.skill.length > 0)
    || (message.model != null && message.model.length > 0);
}

function RunMessageCard({ message }: { message: SchedulerRunMessage }): ReactElement {
  const showMetadata = shouldShowMessageMetadata(message);

  return (
    <div
      className={`p-3 rounded ${message.role === 'user' ? 'bg-primary-subtle text-primary-emphasis' : 'bg-body-tertiary'}`}
    >
      <div className="d-flex justify-content-between gap-2 align-items-start">
        <div className="fw-semibold text-uppercase small">{message.role}</div>
        <div className="small text-body-secondary">{formatTimestamp(message.timestamp)}</div>
      </div>
      <div className="mt-2 sessions-message">{message.content}</div>
      {showMetadata && (
        <div className="mt-2 small text-body-secondary">
          {message.modelTier != null && message.modelTier.length > 0 && <>tier {message.modelTier}</>}
          {message.skill != null && message.skill.length > 0 && <>{message.modelTier != null && message.modelTier.length > 0 ? ' · ' : ''}skill {message.skill}</>}
          {message.model != null && message.model.length > 0 && <>{(message.modelTier != null && message.modelTier.length > 0) || (message.skill != null && message.skill.length > 0) ? ' · ' : ''}model {message.model}</>}
        </div>
      )}
    </div>
  );
}

function RunsList({
  runs,
  runsLoading,
  selectedRunId,
  onSelectRun,
}: RunsListProps): ReactElement {
  if (runsLoading) {
    return (
      <div className="d-flex align-items-center gap-2 text-body-secondary">
        <Spinner size="sm" />
        <span>Loading runs...</span>
      </div>
    );
  }

  if (runs.length === 0) {
    return <div className="text-body-secondary small">No runs recorded yet.</div>;
  }

  return (
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
  );
}

function RunTranscript({
  runDetail,
  runDetailLoading,
  resolveGoalHref,
  resolveTaskHref,
  resolveScheduledTaskHref,
  onNavigate,
}: RunTranscriptProps): ReactElement {
  if (runDetailLoading) {
    return (
      <div className="d-flex align-items-center gap-2 text-body-secondary">
        <Spinner size="sm" />
        <span>Loading transcript...</span>
      </div>
    );
  }

  if (runDetail == null) {
    return <div className="text-body-secondary small">Select a run to inspect its transcript.</div>;
  }

  return (
    <>
      <div className="mb-3 small text-body-secondary">
        <div>Conversation: <code>{runDetail.conversationKey}</code></div>
        {runDetail.goalId != null && runDetail.goalId.length > 0 && (
          <TargetLink
            label={`Goal: ${runDetail.goalLabel ?? runDetail.goalId}`}
            id={runDetail.goalId}
            href={resolveGoalHref(runDetail.goalId)}
            onNavigate={onNavigate}
          />
        )}
        {runDetail.taskId != null && runDetail.taskId.length > 0 && (
          <TargetLink
            label={`Task: ${runDetail.taskLabel ?? runDetail.taskId}`}
            id={runDetail.taskId}
            href={resolveTaskHref(runDetail.taskId)}
            onNavigate={onNavigate}
          />
        )}
        {runDetail.scheduledTaskId != null && runDetail.scheduledTaskId.length > 0 && (
          <TargetLink
            label={`Scheduled task: ${runDetail.scheduledTaskLabel ?? runDetail.scheduledTaskId}`}
            id={runDetail.scheduledTaskId}
            href={resolveScheduledTaskHref(runDetail.scheduledTaskId)}
            onNavigate={onNavigate}
          />
        )}
      </div>

      <div className="d-flex flex-column gap-2">
        {runDetail.messages.map((message) => (
          <RunMessageCard key={message.id} message={message} />
        ))}
      </div>
    </>
  );
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
  resolveGoalHref,
  resolveTaskHref,
  resolveScheduledTaskHref,
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
            <RunsList
              runs={runs}
              runsLoading={runsLoading}
              selectedRunId={selectedRunId}
              onSelectRun={onSelectRun}
            />
          </div>

          <div className="col-lg-8">
            <div className="fw-semibold mb-2">Transcript</div>
            <RunTranscript
              runDetail={runDetail}
              runDetailLoading={runDetailLoading}
              resolveGoalHref={resolveGoalHref}
              resolveTaskHref={resolveTaskHref}
              resolveScheduledTaskHref={resolveScheduledTaskHref}
              onNavigate={onHide}
            />
          </div>
        </div>
      </Modal.Body>
    </Modal>
  );
}
