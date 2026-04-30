import { useState, type ReactNode } from 'react';
import {
  FiAlertCircle,
  FiChevronDown,
  FiChevronRight,
  FiCheckCircle,
  FiCircle,
  FiLoader,
  FiSlash,
  FiSkipForward,
} from 'react-icons/fi';
import type { ToolCallStatus, ToolCallViewModel } from './types';
import { TOOL_STATUS_LABELS, formatDuration } from './agentRunFormat';

interface ToolCallRowProps {
  call: ToolCallViewModel;
  onRetry?: (callId: string) => void;
  onCancel?: (callId: string) => void;
}

const STATUS_ICON: Record<ToolCallStatus, ReactNode> = {
  pending: <FiCircle size={14} aria-hidden="true" />,
  running: <FiLoader size={14} aria-hidden="true" className="animate-spin" />,
  success: <FiCheckCircle size={14} aria-hidden="true" />,
  failed: <FiAlertCircle size={14} aria-hidden="true" />,
  skipped: <FiSkipForward size={14} aria-hidden="true" />,
  cancelled: <FiSlash size={14} aria-hidden="true" />,
};

const STATUS_TONE: Record<ToolCallStatus, string> = {
  pending: 'tool-row__icon--skipped',
  running: 'tool-row__icon--running',
  success: 'tool-row__icon--success',
  failed: 'tool-row__icon--failed',
  skipped: 'tool-row__icon--skipped',
  cancelled: 'tool-row__icon--skipped',
};

function ToolDetails({ call, onRetry, onCancel }: ToolCallRowProps) {
  return (
    <div className="tool-row__details" role="region" aria-label="Tool call details">
      {call.inputPreview != null && (
        <div className="tool-row__details-block">
          <span>Input</span>
          <pre>{call.inputPreview}</pre>
        </div>
      )}
      {call.outputPreview != null && (
        <div className="tool-row__details-block">
          <span>Output</span>
          <pre>{call.outputPreview}</pre>
        </div>
      )}
      {call.errorMessage != null && (
        <div className="tool-row__details-block">
          <span>Error{call.errorCode != null ? ` (${call.errorCode})` : ''}</span>
          <pre>{call.errorMessage}</pre>
        </div>
      )}
      <div className="plan-block__actions">
        {call.status === 'failed' && onRetry != null && (
          <button type="button" className="agent-btn" onClick={() => onRetry(call.id)}>Retry</button>
        )}
        {call.status === 'running' && onCancel != null && (
          <button type="button" className="agent-btn agent-btn--danger" onClick={() => onCancel(call.id)}>Cancel</button>
        )}
      </div>
    </div>
  );
}

export default function ToolCallRow({ call, onRetry, onCancel }: ToolCallRowProps) {
  const [open, setOpen] = useState(false);
  const duration = call.durationMs != null ? formatDuration(call.durationMs) : '—';
  return (
    <>
      <button
        type="button"
        className="tool-row"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
      >
        <span className={`tool-row__icon ${STATUS_TONE[call.status]}`}>{STATUS_ICON[call.status]}</span>
        <span className="tool-row__name">{call.toolName}</span>
        <span className="tool-row__target">{call.displayTarget ?? ''}</span>
        <span className="tool-row__duration">{duration}</span>
        <span className="tool-row__status">{TOOL_STATUS_LABELS[call.status]}</span>
        <span className="tool-row__chevron" aria-hidden="true">
          {open ? <FiChevronDown size={14} /> : <FiChevronRight size={14} />}
        </span>
      </button>
      {open && <ToolDetails call={call} onRetry={onRetry} onCancel={onCancel} />}
    </>
  );
}
