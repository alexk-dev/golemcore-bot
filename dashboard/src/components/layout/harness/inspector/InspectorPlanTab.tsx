import { useChatSessionStore } from '../../../../store/chatSessionStore';
import { usePlanControlState } from '../../../../hooks/usePlans';
import PlanBlock from '../../../agentRun/PlanBlock';
import { SAMPLE_PLAN } from '../../../agentRun/sampleAgentRun';
import StatusPill from '../../../agentRun/StatusPill';

export default function InspectorPlanTab() {
  const sessionId = useChatSessionStore((s) => s.activeSessionId);
  const { data, isLoading } = usePlanControlState(sessionId, sessionId.length > 0);
  const planModeActive = data?.planModeActive === true;

  return (
    <div className="harness-inspector__placeholder-stack">
      <section className="agent-card" aria-label="Plan mode status">
        <div className="agent-card__header">
          <span>Plan mode</span>
          <StatusPill tone={planModeActive ? 'accent' : 'neutral'} showDot>
            {planModeActive ? 'On' : 'Off'}
          </StatusPill>
        </div>
        <div className="harness-inspector__card-row">
          <span className="harness-inspector__card-label">Session</span>
          <span className="harness-inspector__card-value">
            {sessionId.length > 0 ? sessionId.slice(0, 8) : '—'}
          </span>
        </div>
        <div className="harness-inspector__card-row">
          <span className="harness-inspector__card-label">Active plan id</span>
          <span className="harness-inspector__card-value">
            {data?.activePlanId ?? '—'}
          </span>
        </div>
        {isLoading && <p className="harness-inspector__card-label">Loading plan state…</p>}
      </section>
      <p className="harness-inspector__card-label">
        Demo plan — the runtime does not yet emit structured plan steps; the
        block below mirrors §23 of the redesign spec to preview the layout.
      </p>
      <PlanBlock
        plan={SAMPLE_PLAN}
        currentStepIndex={3}
        isRunActive
        hasFailedStep
        onPause={() => undefined}
        onSkip={() => undefined}
        onEdit={() => undefined}
        onRunStepByStep={() => undefined}
        onRetryStep={() => undefined}
      />
    </div>
  );
}
