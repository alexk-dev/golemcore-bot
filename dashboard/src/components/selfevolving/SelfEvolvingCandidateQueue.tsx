import type { ReactElement } from 'react';

import type {
  SelfEvolvingCandidate,
  SelfEvolvingCandidateEvidenceRef,
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

interface SelfEvolvingCandidateQueueProps {
  candidates: SelfEvolvingCandidate[];
  selectedCandidateId: string | null;
  promotingCandidateId: string | null;
  lastPromotionResult: SelfEvolvingPromotionDecision | null;
  lastPromotionError: boolean;
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
  lastPromotionError: boolean;
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

function resolveEvidenceItems(candidate: SelfEvolvingCandidate): SelfEvolvingCandidateEvidenceRef[] {
  return candidate.evidenceRefs ?? [];
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
  lastPromotionError,
  onSelectRun,
  onPlanPromotion,
}: CandidateDetailProps): ReactElement {
  const hasDiff = !isPlaceholderDiff(candidate.proposedDiff);
  const evidenceItems = resolveEvidenceItems(candidate);
  const rationale = resolveCandidateRationale(candidate);
  const behaviorInstructions = resolveCandidateBehavior(candidate);
  const toolInstructions = resolveCandidateToolInstructions(candidate);
  const expectedOutcome = resolveCandidateOutcome(candidate);
  const approvalNotes = resolveCandidateApprovalNotes(candidate);
  const isPromoting = promotingCandidateId === candidate.id;
  const promotionDone = lastPromotionResult?.candidateId === candidate.id;
  const promotionFailed = lastPromotionError && promotingCandidateId == null;
  const canApprove = candidate.status !== 'active' && !promotionDone;

  return (
    <div className="border-t border-border/80 pt-4">
      <div className="mb-4">
        <h6 className="text-sm font-semibold mb-1">What is proposed</h6>
        <p className="text-sm">{resolveCandidateHeadline(candidate)}</p>
      </div>

      {rationale != null ? (
        <div className="mb-4">
          <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Why this change</span>
          <p className="text-sm mt-1 mb-0">{rationale}</p>
        </div>
      ) : null}

      {behaviorInstructions != null ? (
        <div className="mb-4">
          <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Proposed behavior</span>
          <p className="text-sm mt-1 mb-0">{behaviorInstructions}</p>
        </div>
      ) : null}

      {toolInstructions != null ? (
        <div className="mb-4">
          <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Tooling guidance</span>
          <p className="text-sm mt-1 mb-0">{toolInstructions}</p>
        </div>
      ) : null}

      {evidenceItems.length > 0 ? (
        <div className="mb-4">
          <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
            What the judge observed
          </span>
          <div className="flex flex-col gap-2 mt-1">
            {evidenceItems.map((ref, index) => (
              <div
                key={`${ref.traceId}-${ref.spanId}-${index}`}
                className="rounded-lg bg-muted/40 border border-border/60 p-3"
              >
                <p className="text-sm mb-1">
                  {ref.outputFragment != null && ref.outputFragment.length > 0
                    ? ref.outputFragment
                    : 'Evidence anchor recorded without an output fragment.'}
                </p>
                <div className="text-xs text-muted-foreground flex gap-3 flex-wrap">
                  {ref.spanId != null ? (
                    <span>
                      Span: <span className="font-mono">{shortId(ref.spanId)}</span>
                    </span>
                  ) : null}
                  {ref.traceId != null ? (
                    <span>
                      Trace: <span className="font-mono">{shortId(ref.traceId)}</span>
                    </span>
                  ) : null}
                </div>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {hasDiff ? (
        <div className="mb-4">
          <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Proposed patch</span>
          <pre className="mt-1 rounded-lg bg-muted/40 border border-border/60 p-3 text-xs font-mono whitespace-pre-wrap overflow-x-auto max-h-64">
            {candidate.proposedDiff}
          </pre>
        </div>
      ) : null}

      <div className="rounded-xl bg-muted/30 border border-border/60 p-4 mb-4">
        <div className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
          <div>
            <span className="text-xs text-muted-foreground">Artifact</span>
            <div>{candidate.artifactKey ?? humanizeStatus(candidate.artifactType)}</div>
          </div>
          <div>
            <span className="text-xs text-muted-foreground">Risk level</span>
            <div>{humanizeStatus(candidate.riskLevel)}</div>
          </div>
          <div>
            <span className="text-xs text-muted-foreground">Status</span>
            <div>{humanizeStatus(candidate.status)}</div>
          </div>
          <div>
            <span className="text-xs text-muted-foreground">ID</span>
            <div className="font-mono text-xs" title={candidate.id}>
              {shortId(candidate.id)}
            </div>
          </div>
          {expectedOutcome != null ? (
            <div className="col-span-2">
              <span className="text-xs text-muted-foreground">Expected outcome</span>
              <div>{expectedOutcome}</div>
            </div>
          ) : null}
          {approvalNotes != null ? (
            <div className="col-span-2">
              <span className="text-xs text-muted-foreground">Approval notes</span>
              <div>{approvalNotes}</div>
            </div>
          ) : null}
        </div>
      </div>

      <div className="mb-4">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">What happens next</span>
        <p className="text-sm mt-1">{describeCandidateStatus(candidate.status)}</p>
      </div>

      <div className="mb-4">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground mb-2 block">
          Source runs
        </span>
        {candidate.sourceRunIds.length === 0 ? (
          <p className="text-sm text-muted-foreground">No source runs linked.</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {candidate.sourceRunIds.map((runId) => (
              <button
                key={runId}
                type="button"
                className="btn btn-sm btn-secondary font-mono"
                title={`Open run ${runId}`}
                onClick={() => onSelectRun(runId)}
              >
                {shortId(runId)}
              </button>
            ))}
          </div>
        )}
      </div>

      {promotionDone ? (
        <div className="rounded-xl border border-green-300/40 bg-green-50/60 dark:border-green-500/30 dark:bg-green-950/30 p-4 mb-3">
          <p className="text-sm font-semibold mb-2 text-green-800 dark:text-green-300">
            Change approved and activated
          </p>
          <p className="text-sm text-green-700 dark:text-green-400">
            Status: <span className="font-medium">{humanizeStatus(lastPromotionResult.toState)}</span>.
            {lastPromotionResult.toState === 'shadowed'
              ? ' The change is now running in shadow mode alongside the current version. If metrics hold, it will be promoted to full traffic.'
              : null}
            {lastPromotionResult.toState === 'active'
              ? ' The change is now live and serving all traffic.'
              : null}
          </p>
        </div>
      ) : null}

      {promotionFailed ? (
        <div className="rounded-xl border border-destructive/40 bg-destructive/10 p-3 mb-3">
          <p className="text-sm text-destructive">Promotion failed. Check backend logs for details.</p>
        </div>
      ) : null}

      {canApprove ? (
        <button
          type="button"
          className="btn btn-primary"
          title="Approve this change and activate it as a tactic immediately"
          onClick={() => onPlanPromotion(candidate.id)}
          disabled={isPromoting}
        >
          {isPromoting ? 'Approving…' : 'Approve and activate'}
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
  lastPromotionError,
  onSelectCandidate,
  onSelectRun,
  onPlanPromotion,
}: SelfEvolvingCandidateQueueProps): ReactElement {
  const activeCandidate = candidates.find((candidate) => candidate.id === selectedCandidateId) ?? null;

  return (
    <div className="card">
      <div className="card-body">
        <div className="flex items-center justify-between gap-2 mb-1">
          <h5 className="text-base font-semibold tracking-tight mb-0">Proposed Changes</h5>
          <span className="badge text-bg-secondary">{candidates.length}</span>
        </div>
        <p className="text-xs text-muted-foreground mb-4">
          Recent judged runs produced concrete proposals. Review the summary, rationale, evidence, and activation
          status before promoting a change.
        </p>

        {candidates.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No proposed changes yet. They appear here when judged runs produce a concrete improvement proposal.
          </p>
        ) : (
          <div className="flex flex-col gap-2 mb-4">
            {candidates.map((candidate) => (
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
            lastPromotionError={lastPromotionError}
            onSelectRun={onSelectRun}
            onPlanPromotion={onPlanPromotion}
          />
        ) : null}
      </div>
    </div>
  );
}
