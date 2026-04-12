import type { ReactElement } from 'react';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';

import type {
  SelfEvolvingCandidate,
  SelfEvolvingPromotionDecision,
  SelfEvolvingRunDetail,
  SelfEvolvingRunSummary,
  SelfEvolvingTacticSearchResponse,
} from '../api/selfEvolving';
import { SelfEvolvingCandidateQueue } from '../components/selfevolving/SelfEvolvingCandidateQueue';
import { SelfEvolvingRunTable } from '../components/selfevolving/SelfEvolvingRunTable';
import { SelfEvolvingTacticSearchWorkspace } from '../components/selfevolving/SelfEvolvingTacticSearchWorkspace';
import { SelfEvolvingVerdictPanel } from '../components/selfevolving/SelfEvolvingVerdictPanel';

export interface RolloutStages {
  shadowRequired: boolean;
  canaryRequired: boolean;
}

interface RunsPanelProps {
  runs: SelfEvolvingRunSummary[];
  activeRunId: string | null;
  activeRunQuery: UseQueryResult<SelfEvolvingRunDetail, unknown>;
  verdictPanelRef: React.MutableRefObject<HTMLDivElement | null>;
  onSelectRun: (runId: string | null) => void;
}

interface CandidatesPanelProps {
  candidates: SelfEvolvingCandidate[];
  activeCandidateId: string | null;
  rolloutStages: RolloutStages;
  planPromotion: UseMutationResult<SelfEvolvingPromotionDecision, unknown, string>;
  onSelectCandidate: (candidateId: string) => void;
  onSelectRun: (runId: string) => void;
}

interface TacticsPanelProps {
  tacticQuery: string;
  tacticSearchResponse: SelfEvolvingTacticSearchResponse | null;
  selectedTacticId: string | null;
  isDeactivatingTactic: boolean;
  isReactivatingTactic: boolean;
  isDeletingTactic: boolean;
  onQueryChange: (query: string) => void;
  onSelectTacticId: (tacticId: string | null) => void;
  onDeactivateTactic: (tacticId: string) => void;
  onReactivateTactic: (tacticId: string) => void;
  onDeleteTactic: (tacticId: string) => void;
}

export function SelfEvolvingRunsPanel({
  runs,
  activeRunId,
  activeRunQuery,
  verdictPanelRef,
  onSelectRun,
}: RunsPanelProps): ReactElement {
  return (
    <div className="grid grid-cols-1 xl:grid-cols-12 gap-4">
      <div className="xl:col-span-7">
        <SelfEvolvingRunTable runs={runs} selectedRunId={activeRunId} onSelectRun={onSelectRun} />
      </div>
      <div className="xl:col-span-5" ref={verdictPanelRef}>
        <SelfEvolvingVerdictPanel run={activeRunQuery.data} isLoading={activeRunQuery.isLoading} />
      </div>
    </div>
  );
}

export function SelfEvolvingCandidatesPanel({
  candidates,
  activeCandidateId,
  rolloutStages,
  planPromotion,
  onSelectCandidate,
  onSelectRun,
}: CandidatesPanelProps): ReactElement {
  return (
    <SelfEvolvingCandidateQueue
      candidates={candidates}
      selectedCandidateId={activeCandidateId}
      promotingCandidateId={planPromotion.isPending ? (planPromotion.variables ?? null) : null}
      lastPromotionResult={planPromotion.data ?? null}
      lastPromotionErrorCandidateId={planPromotion.isError ? (planPromotion.variables ?? null) : null}
      shadowRequired={rolloutStages.shadowRequired}
      canaryRequired={rolloutStages.canaryRequired}
      onSelectCandidate={onSelectCandidate}
      onSelectRun={onSelectRun}
      onPlanPromotion={(candidateId) => planPromotion.mutate(candidateId)}
    />
  );
}

export function SelfEvolvingTacticsPanel({
  tacticQuery,
  tacticSearchResponse,
  selectedTacticId,
  isDeactivatingTactic,
  isReactivatingTactic,
  isDeletingTactic,
  onQueryChange,
  onSelectTacticId,
  onDeactivateTactic,
  onReactivateTactic,
  onDeleteTactic,
}: TacticsPanelProps): ReactElement {
  return (
    <SelfEvolvingTacticSearchWorkspace
      query={tacticQuery}
      onQueryChange={onQueryChange}
      searchResponse={tacticSearchResponse}
      selectedTacticId={selectedTacticId}
      onSelectTacticId={onSelectTacticId}
      onBackToResults={() => onSelectTacticId(null)}
      onDeactivateTactic={onDeactivateTactic}
      onReactivateTactic={onReactivateTactic}
      onDeleteTactic={onDeleteTactic}
      isDeactivatingTactic={isDeactivatingTactic}
      isReactivatingTactic={isReactivatingTactic}
      isDeletingTactic={isDeletingTactic}
    />
  );
}
