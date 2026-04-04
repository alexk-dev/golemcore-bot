import type { ReactElement } from 'react';

import type {
  SelfEvolvingCandidate,
  SelfEvolvingRunSummary,
} from '../../api/selfEvolving';

interface SelfEvolvingOverviewCardsProps {
  runs: SelfEvolvingRunSummary[];
  candidates: SelfEvolvingCandidate[];
}

function countCompletedRuns(runs: SelfEvolvingRunSummary[]): number {
  return runs.filter((run) => run.outcomeStatus === 'completed' || run.status === 'completed').length;
}

function countPendingCandidates(candidates: SelfEvolvingCandidate[]): number {
  return candidates.filter((candidate) =>
    ['proposed', 'approved_pending', 'approved', 'shadowed', 'canary'].includes(candidate.status ?? ''),
  ).length;
}

export function SelfEvolvingOverviewCards({
  runs,
  candidates,
}: SelfEvolvingOverviewCardsProps): ReactElement {
  const pendingCount = countPendingCandidates(candidates);
  return (
    <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-5">
      <div className="card card-body flex flex-col gap-1" title="Total agent sessions captured by the self-evolving pipeline">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Tracked Runs</span>
        <span className="text-2xl font-bold">{runs.length}</span>
      </div>
      <div className="card card-body flex flex-col gap-1" title="Runs that finished and received a verdict from the judge">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Completed</span>
        <span className="text-2xl font-bold">{countCompletedRuns(runs)}</span>
      </div>
      <div className="card card-body flex flex-col gap-1" title="Candidate improvements that still need review or activation before they go live">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Pending Promotions</span>
        <span className={`text-2xl font-bold ${pendingCount > 0 ? 'text-warning' : ''}`}>{pendingCount}</span>
      </div>
    </div>
  );
}
