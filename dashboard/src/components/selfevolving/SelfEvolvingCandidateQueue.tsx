import type { ReactElement } from 'react';

import type {
  SelfEvolvingCandidate,
  SelfEvolvingPromotionDecision,
} from '../../api/selfEvolving';
import {
  describeCandidateStatus,
  describeGoal,
  humanizeStatus,
  isPlaceholderDiff,
  riskBadge,
  shortId,
  statusBadgeClass,
} from './selfEvolvingUi';
import {
  CandidateEvidenceSection,
  CandidateMetadataSection,
  CandidateNarrativeSections,
  PromotionResultSection,
  SourceRunsSection,
} from './SelfEvolvingCandidateDetailSections';
import { SelfEvolvingRolloutPipeline } from './SelfEvolvingRolloutPipeline';
import { resolveCurrentStageKey, resolveNextStageKey, stageLabel } from './SelfEvolvingRolloutPipelineUtils';

interface SelfEvolvingCandidateQueueProps {
  candidates: SelfEvolvingCandidate[];
  selectedCandidateId: string | null;
  promotingCandidateId: string | null;
  lastPromotionResult: SelfEvolvingPromotionDecision | null;
  lastPromotionErrorCandidateId: string | null;
  shadowRequired?: boolean;
  canaryRequired?: boolean;
  onSelectCandidate: (candidateId: string) => void;
  onSelectRun: (runId: string) => void;
  onPlanPromotion: (candidateId: string) => void;
}

interface CandidateCardProps {
  candidate: SelfEvolvingCandidate;
  isSelected: boolean;
  onSelect: () => void;
}

interface CandidateDetailProps {
  candidate: SelfEvolvingCandidate;
  promotingCandidateId: string | null;
  lastPromotionResult: SelfEvolvingPromotionDecision | null;
  lastPromotionErrorCandidateId: string | null;
  shadowRequired: boolean;
  canaryRequired: boolean;
  onSelectRun: (runId: string) => void;
  onPlanPromotion: (candidateId: string) => void;
}

function resolveCandidateHeadline(candidate: SelfEvolvingCandidate): string {
  return candidate.proposal?.summary ?? candidate.expectedImpact ?? 'No description available.';
}

function resolveCandidateRationale(candidate: SelfEvolvingCandidate): string | null {
  return candidate.proposal?.rationale ?? null;
}

function resolveCandidateBehavior(candidate: SelfEvolvingCandidate): string | null {
  return candidate.proposal?.behaviorInstructions ?? null;
}

function resolveCandidateToolInstructions(candidate: SelfEvolvingCandidate): string | null {
  return candidate.proposal?.toolInstructions ?? null;
}

function resolveCandidateOutcome(candidate: SelfEvolvingCandidate): string | null {
  return candidate.proposal?.expectedOutcome ?? candidate.expectedImpact ?? null;
}

function resolveCandidateApprovalNotes(candidate: SelfEvolvingCandidate): string | null {
  return candidate.proposal?.approvalNotes ?? null;
}

function resolvePromotionActionLabel(candidate: SelfEvolvingCandidate, nextStage: string | null): string {
  if (nextStage == null) {
    return 'Advance rollout';
  }
  const nextLabel = stageLabel(nextStage);
  if (nextStage === 'active') {
    return `Promote to active (→ ${nextLabel})`;
  }
  if (candidate.status === 'shadowed' || candidate.status === 'approved' || candidate.status === 'approved_pending') {
    return `Advance rollout (→ ${nextLabel})`;
  }
  return `Approve rollout (→ ${nextLabel})`;
}

function resolvePromotionActionTitle(currentStage: string, nextStage: string | null): string {
  if (nextStage == null) {
    return 'No further rollout stages';
  }
  return `Advance this change: ${stageLabel(currentStage)} → ${stageLabel(nextStage)}`;
}

function CandidateCard({ candidate, isSelected, onSelect }: CandidateCardProps): ReactElement {
  const risk = riskBadge(candidate.riskLevel);

  return (
    <button
      type="button"
      className={`w-full text-left rounded-xl border p-3 transition-colors ${
        isSelected
          ? 'border-primary/50 bg-primary/5'
          : 'border-border/60 hover:border-border hover:bg-muted/30'
      }`}
      onClick={onSelect}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="font-semibold text-sm">{describeGoal(candidate.goal)}</span>
            <span className={`badge ${statusBadgeClass(candidate.status)}`} title="Current workflow status">
              {humanizeStatus(candidate.status)}
            </span>
            <span className={`badge ${risk.className}`} title="Estimated risk level">
              {risk.label}
            </span>
          </div>
          <div className="text-xs text-muted-foreground mt-1 flex items-center gap-2">
            <span className="font-mono" title={candidate.id}>
              {shortId(candidate.id)}
            </span>
            {candidate.artifactKey != null ? (
              <>
                <span className="opacity-40">&middot;</span>
                <span title="Artifact affected by this change">{candidate.artifactKey}</span>
              </>
            ) : null}
            {candidate.artifactKey == null && candidate.artifactType != null ? (
              <>
                <span className="opacity-40">&middot;</span>
                <span>{humanizeStatus(candidate.artifactType)}</span>
              </>
            ) : null}
          </div>
        </div>
        {isSelected ? <span className="text-xs font-medium text-primary shrink-0">Selected</span> : null}
      </div>
      <p className="text-xs text-muted-foreground mt-2 mb-0 line-clamp-2">{resolveCandidateHeadline(candidate)}</p>
    </button>
  );
}

function CandidateDetail({
  candidate,
  promotingCandidateId,
  lastPromotionResult,
  lastPromotionErrorCandidateId,
  shadowRequired,
  canaryRequired,
  onSelectRun,
  onPlanPromotion,
}: CandidateDetailProps): ReactElement {
  const hasDiff = !isPlaceholderDiff(candidate.proposedDiff);
  const evidenceItems = candidate.evidenceRefs ?? [];
  const rationale = resolveCandidateRationale(candidate);
  const behaviorInstructions = resolveCandidateBehavior(candidate);
  const toolInstructions = resolveCandidateToolInstructions(candidate);
  const expectedOutcome = resolveCandidateOutcome(candidate);
  const approvalNotes = resolveCandidateApprovalNotes(candidate);
  const isPromoting = promotingCandidateId === candidate.id;
  const promotionDone = lastPromotionResult?.candidateId === candidate.id;
  const promotionFailed = lastPromotionErrorCandidateId === candidate.id;
  const isTerminal = candidate.status === 'active' || candidate.status === 'reverted' || candidate.status === 'rejected';
  const canApprove = !isTerminal && !promotionDone;
  const currentStage = resolveCurrentStageKey(candidate.status ?? '');
  const nextStage = resolveNextStageKey(currentStage, shadowRequired, canaryRequired);
  const promotionActionLabel = resolvePromotionActionLabel(candidate, nextStage);
  const promotionActionTitle = resolvePromotionActionTitle(currentStage, nextStage);

  return (
    <div className="border-t border-border/80 pt-4">
      <div className="mb-4">
        <h6 className="text-sm font-semibold mb-1">What is proposed</h6>
        <p className="text-sm">{resolveCandidateHeadline(candidate)}</p>
      </div>

      <CandidateNarrativeSections
        rationale={rationale}
        behaviorInstructions={behaviorInstructions}
        toolInstructions={toolInstructions}
      />

      <CandidateEvidenceSection evidenceItems={evidenceItems} />

      {hasDiff ? (
        <div className="mb-4">
          <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Proposed patch</span>
          <pre className="mt-1 rounded-lg bg-muted/40 border border-border/60 p-3 text-xs font-mono whitespace-pre-wrap overflow-x-auto max-h-64">
            {candidate.proposedDiff}
          </pre>
        </div>
      ) : null}

      <CandidateMetadataSection
        candidate={candidate}
        expectedOutcome={expectedOutcome}
        approvalNotes={approvalNotes}
      />

      <SelfEvolvingRolloutPipeline currentStage={currentStage} nextStage={nextStage} />

      <div className="mb-4">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">What happens next</span>
        <p className="text-sm mt-1">{describeCandidateStatus(candidate.status)}</p>
      </div>

      <SourceRunsSection runIds={candidate.sourceRunIds} onSelectRun={onSelectRun} />

      <PromotionResultSection
        promotionDone={promotionDone}
        promotionFailed={promotionFailed}
        lastPromotionResult={lastPromotionResult}
      />

      {canApprove ? (
        <button
          type="button"
          className="btn btn-primary"
          title={promotionActionTitle}
          onClick={() => onPlanPromotion(candidate.id)}
          disabled={isPromoting}
        >
          {isPromoting ? 'Advancing…' : promotionActionLabel}
        </button>
      ) : null}
    </div>
  );
}

export function SelfEvolvingCandidateQueue({
  candidates,
  selectedCandidateId,
  promotingCandidateId,
  lastPromotionResult,
  lastPromotionErrorCandidateId,
  shadowRequired = true,
  canaryRequired = true,
  onSelectCandidate,
  onSelectRun,
  onPlanPromotion,
}: SelfEvolvingCandidateQueueProps): ReactElement {
  const reviewableCandidates = candidates.filter(
    (candidate) => candidate.status !== 'active' && candidate.status !== 'rejected',
  );
  const activeCandidate =
    reviewableCandidates.find((candidate) => candidate.id === selectedCandidateId) ?? reviewableCandidates[0] ?? null;

  return (
    <div className="card">
      <div className="card-body">
        <div className="flex items-center justify-between gap-2 mb-1">
          <h5 className="text-base font-semibold tracking-tight mb-0">Proposed Changes</h5>
          <span className="badge text-bg-secondary">{reviewableCandidates.length}</span>
        </div>
        <p className="text-xs text-muted-foreground mb-4">
          Recent judged runs produced concrete proposals. Review the summary, rationale, evidence, and activation
          status before promoting a change.
        </p>

        {reviewableCandidates.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No proposed changes yet. They appear here when judged runs produce a concrete improvement proposal.
          </p>
        ) : (
          <div className="flex flex-col gap-2 mb-4">
            {reviewableCandidates.map((candidate) => (
              <CandidateCard
                key={candidate.id}
                candidate={candidate}
                isSelected={activeCandidate?.id === candidate.id}
                onSelect={() => onSelectCandidate(candidate.id)}
              />
            ))}
          </div>
        )}

        {activeCandidate != null ? (
            <CandidateDetail
              candidate={activeCandidate}
              promotingCandidateId={promotingCandidateId}
              lastPromotionResult={lastPromotionResult}
              lastPromotionErrorCandidateId={lastPromotionErrorCandidateId}
              shadowRequired={shadowRequired}
              canaryRequired={canaryRequired}
              onSelectRun={onSelectRun}
              onPlanPromotion={onPlanPromotion}
          />
        ) : null}
      </div>
    </div>
  );
}
