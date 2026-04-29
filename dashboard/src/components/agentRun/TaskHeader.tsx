import { useState, type FormEvent, type ReactNode } from 'react';
import { FiEdit2, FiMoreHorizontal, FiShare2 } from 'react-icons/fi';
import StatusPill, { type StatusPillTone } from './StatusPill';
import { RUN_STATUS_LABELS, formatDuration, formatTimeOfDay } from './agentRunFormat';
import type { AgentRunStatus } from './types';

interface TaskHeaderProps {
  title: string;
  status: AgentRunStatus;
  startedAt: string;
  durationMs: number;
  stepCount: number;
  onTitleChange?: (next: string) => void;
  onShare?: () => void;
  onMore?: () => void;
  trailing?: ReactNode;
}

const STATUS_TONE: Record<AgentRunStatus, StatusPillTone> = {
  idle: 'neutral',
  running: 'accent',
  waiting_approval: 'warning',
  waiting_retry: 'warning',
  paused: 'neutral',
  completed: 'success',
  failed: 'danger',
  cancelled: 'neutral',
};

interface TitleEditorProps {
  initial: string;
  onSubmit: (next: string) => void;
  onCancel: () => void;
}

function TitleEditor({ initial, onSubmit, onCancel }: TitleEditorProps) {
  const [draft, setDraft] = useState(initial);
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    const trimmed = draft.trim();
    if (trimmed.length === 0) {
      onCancel();
      return;
    }
    onSubmit(trimmed);
  };
  return (
    <form className="task-header__title-row" onSubmit={handleSubmit}>
      <input
        autoFocus
        className="form-control"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={handleSubmit}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            onCancel();
          }
        }}
        aria-label="Task title"
      />
    </form>
  );
}

export default function TaskHeader({
  title,
  status,
  startedAt,
  durationMs,
  stepCount,
  onTitleChange,
  onShare,
  onMore,
  trailing,
}: TaskHeaderProps) {
  const [editing, setEditing] = useState(false);
  const startLabel = formatTimeOfDay(startedAt);
  const durationLabel = formatDuration(durationMs);

  return (
    <header className="task-header" aria-label="Run summary">
      <div>
        {editing && onTitleChange != null ? (
          <TitleEditor
            initial={title}
            onSubmit={(next) => {
              onTitleChange(next);
              setEditing(false);
            }}
            onCancel={() => setEditing(false)}
          />
        ) : (
          <div className="task-header__title-row">
            <h1 className="task-header__title">{title}</h1>
            {onTitleChange != null && (
              <button
                type="button"
                className="task-header__edit"
                onClick={() => setEditing(true)}
                aria-label="Edit task title"
              >
                <FiEdit2 size={14} aria-hidden="true" />
              </button>
            )}
            <StatusPill tone={STATUS_TONE[status]} showDot>
              {RUN_STATUS_LABELS[status]}
            </StatusPill>
          </div>
        )}
        <div className="task-header__subtitle">
          {startLabel.length > 0 && <span>Started {startLabel}</span>}
          <span className="task-header__subtitle-sep" aria-hidden="true">·</span>
          <span>{durationLabel}</span>
          <span className="task-header__subtitle-sep" aria-hidden="true">·</span>
          <span>{stepCount} {stepCount === 1 ? 'step' : 'steps'}</span>
        </div>
      </div>
      <div className="task-header__actions">
        {trailing}
        {onShare != null && (
          <button type="button" className="agent-btn" onClick={onShare}>
            <FiShare2 size={14} aria-hidden="true" />
            <span>Share</span>
          </button>
        )}
        {onMore != null && (
          <button
            type="button"
            className="agent-btn"
            onClick={onMore}
            aria-label="More actions"
          >
            <FiMoreHorizontal size={16} aria-hidden="true" />
          </button>
        )}
      </div>
    </header>
  );
}
