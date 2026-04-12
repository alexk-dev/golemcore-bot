import { useCallback, useMemo } from 'react';

import type { SelfEvolvingCandidate, SelfEvolvingRunSummary } from '../api/selfEvolving';
import type { RolloutStages } from './SelfEvolvingPageTabPanels';

interface UseSelfEvolvingPageStateArgs {
  selectedRunId: string | null;
  selectedCandidateId: string | null;
  runs: SelfEvolvingRunSummary[];
  candidates: SelfEvolvingCandidate[];
  shadowRequired: boolean | null | undefined;
  canaryRequired: boolean | null | undefined;
  setSelectedRunId: (runId: string | null) => void;
  setSelectedTacticId: (tacticId: string | null) => void;
  setTacticQuery: (query: string) => void;
  setActiveTab: (tab: 'runs') => void;
}

export function useSelfEvolvingPageState({
  selectedRunId,
  selectedCandidateId,
  runs,
  candidates,
  shadowRequired,
  canaryRequired,
  setSelectedRunId,
  setSelectedTacticId,
  setTacticQuery,
  setActiveTab,
}: UseSelfEvolvingPageStateArgs): {
  activeRunId: string | null;
  activeCandidateId: string | null;
  rolloutStages: RolloutStages;
  handleSelectRunFromCandidate: (runId: string) => void;
  handleTacticQueryChange: (query: string) => void;
} {
  const rolloutStages = useMemo(
    () => ({ shadowRequired: shadowRequired ?? true, canaryRequired: canaryRequired ?? true }),
    [canaryRequired, shadowRequired],
  );
  const handleSelectRunFromCandidate = useCallback((runId: string): void => {
    setSelectedRunId(runId);
    setActiveTab('runs');
  }, [setActiveTab, setSelectedRunId]);
  const handleTacticQueryChange = useCallback((nextQuery: string): void => {
    setTacticQuery(nextQuery);
    setSelectedTacticId(null);
  }, [setSelectedTacticId, setTacticQuery]);

  return {
    activeRunId: selectedRunId ?? runs[0]?.id ?? null,
    activeCandidateId: selectedCandidateId ?? candidates[0]?.id ?? null,
    rolloutStages,
    handleSelectRunFromCandidate,
    handleTacticQueryChange,
  };
}
