import { FiCheck, FiX } from 'react-icons/fi';
import type { ApprovalRequestViewModel } from './types';
import { formatTimeOfDay } from './agentRunFormat';

interface ApprovalRequestCardProps {
  approval: ApprovalRequestViewModel;
  onApprove?: () => void;
  onReject?: () => void;
}

export default function ApprovalRequestCard({ approval, onApprove, onReject }: ApprovalRequestCardProps) {
  return (
    <section className="approval-card" role="alert" aria-label={`Approval request: ${approval.title}`}>
      <header className="agent-card__header">
        <span>{approval.title}</span>
        <span className="incident-card__time">{formatTimeOfDay(approval.createdAt)}</span>
      </header>
      <p className="incident-card__body">{approval.description}</p>
      <pre className="tool-row__details">{approval.prompt}</pre>
      <div className="incident-card__actions">
        {onApprove != null && (
          <button type="button" className="agent-btn agent-btn--primary" onClick={onApprove}>
            <FiCheck size={14} aria-hidden="true" />
            <span>Approve</span>
          </button>
        )}
        {onReject != null && (
          <button type="button" className="agent-btn agent-btn--danger" onClick={onReject}>
            <FiX size={14} aria-hidden="true" />
            <span>Reject</span>
          </button>
        )}
      </div>
    </section>
  );
}
