import { FiAlertCircle, FiCheckCircle, FiCircle, FiLoader, FiSkipForward, FiUserCheck } from 'react-icons/fi';
import type { ReactNode } from 'react';
import type { PlanStepStatus, PlanStepViewModel } from './types';
import { PLAN_STATUS_LABELS } from './agentRunFormat';

interface PlanStepRowProps {
  step: PlanStepViewModel;
  isCurrent: boolean;
}

const ICON_BY_STATUS: Record<PlanStepStatus, ReactNode> = {
  pending: <FiCircle size={14} aria-hidden="true" />,
  running: <FiLoader size={14} aria-hidden="true" className="animate-spin" />,
  completed: <FiCheckCircle size={14} aria-hidden="true" />,
  failed: <FiAlertCircle size={14} aria-hidden="true" />,
  skipped: <FiSkipForward size={14} aria-hidden="true" />,
  waiting_approval: <FiUserCheck size={14} aria-hidden="true" />,
};

export default function PlanStepRow({ step, isCurrent }: PlanStepRowProps) {
  const className = `plan-step plan-step--${step.status}${isCurrent ? ' plan-step--current' : ''}`;
  return (
    <div className={className} role="listitem" aria-label={`Step ${step.index}: ${step.title} — ${PLAN_STATUS_LABELS[step.status]}`}>
      <span className="plan-step__index" aria-hidden="true">{ICON_BY_STATUS[step.status] ?? step.index}</span>
      <span className="plan-step__title">{step.title}</span>
      <span className="plan-step__status">{PLAN_STATUS_LABELS[step.status]}</span>
    </div>
  );
}
