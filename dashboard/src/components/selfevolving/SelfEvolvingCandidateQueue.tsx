import type { ReactElement } from 'react';

import type { SelfEvolvingCandidate, SelfEvolvingPromotionDecision } from '../../api/selfEvolving';
import {
  shortId,
  humanizeStatus,
  statusBadgeClass,
  describeGoal,
  describeCandidateStatus,
  riskBadge,
  isPlaceholderDiff,
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

function CandidateCard({
  candidate,
  isSelected,
  onSelect,
}: { candidate: SelfEvolvingCandidate; isSelected: boolean; onSelect: () => void }): ReactElement {
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
            <span className="font-mono" title={candidate.id}>{shortId(candidate.id)}</span>
            {candidate.artifactKey != null && (
              <>
                <span className="opacity-40">&middot;</span>
                <span title="Artifact affected by this change">{candidate.artifactKey}</span>
              </>
            )}
            {candidate.artifactKey == null && candidate.artifactType != null && (
              <>
                <span className="opacity-40">&middot;</span>
                <span>{humanizeStatus(candidate.artifactType)}</span>
              </>
            )}
          </div>
        </div>
        {isSelected && (
          <span className="text-xs font-medium text-primary shrink-0">Selected</span>
        )}
      </div>
      {candidate.expectedImpact != null && (
        <p className="text-xs text-muted-foreground mt-2 mb-0 line-clamp-2">{candidate.expectedImpact}</p>
      )}
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
}: {
  candidate: SelfEvolvingCandidate;
  promotingCandidateId: string | null;
  lastPromotionResult: SelfEvolvingPromotionDecision | null;
  lastPromotionError: boolean;
  onSelectRun: (runId: string) => void;
  onPlanPromotion: (candidateId: string) => void;
}): ReactElement {
  const hasDiff = !isPlaceholderDiff(candidate.proposedDiff);
  const evidenceFragments = (candidate.evidenceRefs ?? [])
    .filter((ref) => ref.outputFragment != null && ref.outputFragment.length > 0);
  const isPromoting = promotingCandidateId === candidate.id;
  const promotionDone = lastPromotionResult?.candidateId === candidate.id;
  const promotionFailed = lastPromotionError && promotingCandidateId == null;

  return (
    <div className="border-t border-border/80 pt-4">
      {/* 1. What is proposed — the most important part */}
      <div className="mb-4">
        <h6 className="text-sm font-semibold mb-1">What is proposed</h6>
        <p className="text-sm">{candidate.expectedImpact ?? 'No description available.'}</p>
      </div>

      {/* 2. Evidence — what the judge observed */}
      {evidenceFragments.length > 0 && (
        <div className="mb-4">
          <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">What the judge observed</span>
          <div className="flex flex-col gap-2 mt-1">
            {evidenceFragments.map((ref, index) => (
              <div key={`${ref.traceId}-${ref.spanId}-${index}`} className="rounded-lg bg-muted/40 border border-border/60 p-3">
                <p className="text-sm mb-1">{ref.outputFragment}</p>
                <div className="text-xs text-muted-foreground flex gap-3">
                  {ref.spanId != null && <span>Span: <span className="font-mono">{shortId(ref.spanId)}</span></span>}
                  {ref.traceId != null && <span>Trace: <span className="font-mono">{shortId(ref.traceId)}</span></span>}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 3. Diff — if real content exists */}
      {hasDiff && (
        <div className="mb-4">
          <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Proposed diff</span>
          <pre className="mt-1 rounded-lg bg-muted/40 border border-border/60 p-3 text-xs font-mono whitespace-pre-wrap overflow-x-auto max-h-64">{candidate.proposedDiff}</pre>
        </div>
      )}

      {/* 4. Metadata */}
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
            <div className="font-mono text-xs" title={candidate.id}>{shortId(candidate.id)}</div>
          </div>
        </div>
      </div>

      {/* 5. What happens next */}
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

      {promotionDone && (
        <div className="rounded-xl border border-green-300/40 bg-green-50/60 dark:border-green-500/30 dark:bg-green-950/30 p-4 mb-3">
          <p className="text-sm font-semibold mb-2 text-green-800 dark:text-green-300">Change approved and activated</p>
          <p className="text-sm text-green-700 dark:text-green-400">
            Status: <span className="font-medium">{humanizeStatus(lastPromotionResult.toState)}</span>.
            {lastPromotionResult.toState === 'shadowed' && ' The change is now running in shadow mode alongside the current version. If metrics hold, it will be promoted to full traffic.'}
            {lastPromotionResult.toState === 'active' && ' The change is now live and serving all traffic.'}
          </p>
        </div>
      )}

      {promotionFailed && (
        <div className="rounded-xl border border-destructive/40 bg-destructive/10 p-3 mb-3">
          <p className="text-sm text-destructive">Promotion failed. Check backend logs for details.</p>
        </div>
      )}

      {!promotionDone && (
        <button
          type="button"
          className="btn btn-primary"
          title="Approve this change and activate it as a tactic immediately"
          onClick={() => onPlanPromotion(candidate.id)}
          disabled={isPromoting}
        >
          {isPromoting ? 'Approving\u2026' : 'Approve and activate'}
        </button>
      )}
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
          The judge analyzed recent runs and proposed these improvements. Review each one and decide whether to promote it to production.
        </p>

        {candidates.length === 0 ? (
          <p className="text-sm text-muted-foreground">No proposed changes yet. They appear here when the judge finds improvements after analyzing runs.</p>
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

        {activeCandidate != null && (
          <CandidateDetail
            candidate={activeCandidate}
            promotingCandidateId={promotingCandidateId}
            lastPromotionResult={lastPromotionResult}
            lastPromotionError={lastPromotionError}
            onSelectRun={onSelectRun}
            onPlanPromotion={onPlanPromotion}
          />
        )}
      </div>
    </div>
  );
}
