import type { ReactElement } from 'react';

import type {
  SelfEvolvingCandidate,
  SelfEvolvingCandidateEvidenceRef,
  SelfEvolvingPromotionDecision,
} from '../../api/selfEvolving';
import { humanizeStatus, shortId } from './selfEvolvingUi';

export interface CandidateNarrativeSectionsProps {
  rationale: string | null;
  behaviorInstructions: string | null;
  toolInstructions: string | null;
}

export interface CandidateEvidenceSectionProps {
  evidenceItems: SelfEvolvingCandidateEvidenceRef[];
}

export interface CandidateMetadataSectionProps {
  candidate: SelfEvolvingCandidate;
  expectedOutcome: string | null;
  approvalNotes: string | null;
}

export interface PromotionResultSectionProps {
  promotionDone: boolean;
  promotionFailed: boolean;
  lastPromotionResult: SelfEvolvingPromotionDecision | null;
}

export interface SourceRunsSectionProps {
  runIds: string[];
  onSelectRun: (runId: string) => void;
}

export function CandidateNarrativeSections({
  rationale,
  behaviorInstructions,
  toolInstructions,
}: CandidateNarrativeSectionsProps): ReactElement {
  return (
    <>
      <CandidateTextBlock title="Why this change" text={rationale} />
      <CandidateTextBlock title="Proposed behavior" text={behaviorInstructions} />
      <CandidateTextBlock title="Tooling guidance" text={toolInstructions} />
    </>
  );
}

function CandidateTextBlock({ title, text }: { title: string; text: string | null }): ReactElement | null {
  if (text == null) {
    return null;
  }
  return (
    <div className="mb-4">
      <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{title}</span>
      <p className="text-sm mt-1 mb-0">{text}</p>
    </div>
  );
}

export function CandidateEvidenceSection({ evidenceItems }: CandidateEvidenceSectionProps): ReactElement | null {
  if (evidenceItems.length === 0) {
    return null;
  }
  return (
    <div className="mb-4">
      <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
        What the judge observed
      </span>
      <div className="flex flex-col gap-2 mt-1">
        {evidenceItems.map((ref, index) => (
          <div key={`${ref.traceId}-${ref.spanId}-${index}`} className="rounded-lg bg-muted/40 border border-border/60 p-3">
            <p className="text-sm mb-1">{resolveEvidenceText(ref)}</p>
            <div className="text-xs text-muted-foreground flex gap-3 flex-wrap">
              {ref.spanId != null ? <span>Span: <span className="font-mono">{shortId(ref.spanId)}</span></span> : null}
              {ref.traceId != null ? <span>Trace: <span className="font-mono">{shortId(ref.traceId)}</span></span> : null}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function resolveEvidenceText(ref: SelfEvolvingCandidateEvidenceRef): string {
  return ref.outputFragment != null && ref.outputFragment.length > 0
    ? ref.outputFragment
    : 'Evidence anchor recorded without an output fragment.';
}

export function CandidateMetadataSection({
  candidate,
  expectedOutcome,
  approvalNotes,
}: CandidateMetadataSectionProps): ReactElement {
  return (
    <div className="rounded-xl bg-muted/30 border border-border/60 p-4 mb-4">
      <div className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
        <div><span className="text-xs text-muted-foreground">Artifact</span><div>{candidate.artifactKey ?? humanizeStatus(candidate.artifactType)}</div></div>
        <div><span className="text-xs text-muted-foreground">Risk level</span><div>{humanizeStatus(candidate.riskLevel)}</div></div>
        <div><span className="text-xs text-muted-foreground">Status</span><div>{humanizeStatus(candidate.status)}</div></div>
        <div><span className="text-xs text-muted-foreground">ID</span><div className="font-mono text-xs" title={candidate.id}>{shortId(candidate.id)}</div></div>
        {expectedOutcome != null ? (
          <div className="col-span-2"><span className="text-xs text-muted-foreground">Expected outcome</span><div>{expectedOutcome}</div></div>
        ) : null}
        {approvalNotes != null ? (
          <div className="col-span-2"><span className="text-xs text-muted-foreground">Approval notes</span><div>{approvalNotes}</div></div>
        ) : null}
      </div>
    </div>
  );
}

export function PromotionResultSection({
  promotionDone,
  promotionFailed,
  lastPromotionResult,
}: PromotionResultSectionProps): ReactElement | null {
  if (promotionDone && lastPromotionResult != null) {
    return (
      <div className="rounded-xl border border-green-300/40 bg-green-50/60 dark:border-green-500/30 dark:bg-green-950/30 p-4 mb-3">
        <p className="text-sm font-semibold mb-2 text-green-800 dark:text-green-300">Rollout advanced</p>
        <p className="text-sm text-green-700 dark:text-green-400">
          Status: <span className="font-medium">{humanizeStatus(lastPromotionResult.toState)}</span>.
          {resolvePromotionDoneCopy(lastPromotionResult.toState)}
        </p>
      </div>
    );
  }
  if (promotionFailed) {
    return (
      <div className="rounded-xl border border-destructive/40 bg-destructive/10 p-3 mb-3">
        <p className="text-sm text-destructive">Promotion failed. Check backend logs for details.</p>
      </div>
    );
  }
  return null;
}

function resolvePromotionDoneCopy(toState: string | null): string | null {
  if (toState === 'shadowed') {
    return ' The change is now running in shadow mode alongside the current version. If metrics hold, it will be promoted to full traffic.';
  }
  if (toState === 'canary') {
    return ' The change is now in canary rollout with limited exposure before full activation.';
  }
  if (toState === 'active') {
    return ' The change is now live and serving all traffic.';
  }
  return null;
}

export function SourceRunsSection({ runIds, onSelectRun }: SourceRunsSectionProps): ReactElement {
  return (
    <div className="mb-4">
      <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground mb-2 block">Source runs</span>
      {runIds.length === 0 ? (
        <p className="text-sm text-muted-foreground">No source runs linked.</p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {runIds.map((runId) => (
            <button key={runId} type="button" className="btn btn-sm btn-secondary font-mono" title={`Open run ${runId}`} onClick={() => onSelectRun(runId)}>
              {shortId(runId)}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
