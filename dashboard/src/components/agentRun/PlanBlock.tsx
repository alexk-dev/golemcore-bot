import { useState } from 'react';
import { FiChevronDown, FiChevronRight } from 'react-icons/fi';
import type { PlanViewModel } from './types';
import PlanStepRow from './PlanStepRow';

export interface PlanBlockActions {
  onApprove?: () => void;
  onPause?: () => void;
  onSkip?: () => void;
  onEdit?: () => void;
  onRunStepByStep?: () => void;
  onRetryStep?: () => void;
}

interface PlanBlockProps extends PlanBlockActions {
  plan: PlanViewModel;
  currentStepIndex?: number;
  initiallyOpen?: boolean;
  needsApproval?: boolean;
  isRunActive?: boolean;
  hasFailedStep?: boolean;
}

interface ActionsRowProps extends PlanBlockActions {
  needsApproval: boolean;
  isRunActive: boolean;
  hasFailedStep: boolean;
}

function ActionsRow({
  needsApproval,
  isRunActive,
  hasFailedStep,
  onApprove,
  onPause,
  onSkip,
  onEdit,
  onRunStepByStep,
  onRetryStep,
}: ActionsRowProps) {
  return (
    <div className="plan-block__actions">
      {onEdit != null && (
        <button type="button" className="agent-btn" onClick={onEdit}>Edit plan</button>
      )}
      {needsApproval && onApprove != null && (
        <button type="button" className="agent-btn agent-btn--primary" onClick={onApprove}>Approve</button>
      )}
      {onRunStepByStep != null && (
        <button type="button" className="agent-btn" onClick={onRunStepByStep}>Run step-by-step</button>
      )}
      {isRunActive && onPause != null && (
        <button type="button" className="agent-btn" onClick={onPause}>Pause</button>
      )}
      {onSkip != null && (
        <button type="button" className="agent-btn" onClick={onSkip}>Skip step</button>
      )}
      {hasFailedStep && onRetryStep != null && (
        <button type="button" className="agent-btn agent-btn--danger" onClick={onRetryStep}>Retry step</button>
      )}
    </div>
  );
}

export default function PlanBlock({
  plan,
  currentStepIndex,
  initiallyOpen = true,
  needsApproval = false,
  isRunActive = false,
  hasFailedStep = false,
  ...actions
}: PlanBlockProps) {
  const [open, setOpen] = useState(initiallyOpen);
  const stepCount = plan.steps.length;

  return (
    <section className="agent-card plan-block" aria-label="Plan">
      <button
        type="button"
        className="agent-card__header"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
        aria-controls={`plan-${plan.id}-steps`}
      >
        {open ? <FiChevronDown size={14} aria-hidden="true" /> : <FiChevronRight size={14} aria-hidden="true" />}
        <span>Plan</span>
        <span className="agent-card__count">{stepCount} {stepCount === 1 ? 'step' : 'steps'}</span>
      </button>
      {open && (
        <div className="plan-block__steps" id={`plan-${plan.id}-steps`} role="list">
          {plan.steps.map((step) => (
            <PlanStepRow
              key={step.id}
              step={step}
              isCurrent={step.index === currentStepIndex}
            />
          ))}
        </div>
      )}
      {open && (
        <ActionsRow
          needsApproval={needsApproval}
          isRunActive={isRunActive}
          hasFailedStep={hasFailedStep}
          {...actions}
        />
      )}
    </section>
  );
}
