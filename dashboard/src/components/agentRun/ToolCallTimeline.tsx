import { useState } from 'react';
import { FiChevronDown, FiChevronRight, FiTool } from 'react-icons/fi';
import type { ToolCallViewModel } from './types';
import ToolCallRow from './ToolCallRow';

interface ToolCallTimelineProps {
  calls: ToolCallViewModel[];
  initiallyOpen?: boolean;
  onRetry?: (callId: string) => void;
  onCancel?: (callId: string) => void;
}

export default function ToolCallTimeline({
  calls,
  initiallyOpen = true,
  onRetry,
  onCancel,
}: ToolCallTimelineProps) {
  const [open, setOpen] = useState(initiallyOpen);

  return (
    <section className="agent-card tool-timeline-card" aria-label="Tool calls">
      <button
        type="button"
        className="agent-card__header"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
      >
        {open ? <FiChevronDown size={14} aria-hidden="true" /> : <FiChevronRight size={14} aria-hidden="true" />}
        <FiTool size={14} aria-hidden="true" />
        <span>Tool calls</span>
        <span className="agent-card__count">{calls.length}</span>
      </button>
      {open && (
        <div className="tool-timeline" role="list">
          {calls.map((call) => (
            <ToolCallRow key={call.id} call={call} onRetry={onRetry} onCancel={onCancel} />
          ))}
        </div>
      )}
    </section>
  );
}
