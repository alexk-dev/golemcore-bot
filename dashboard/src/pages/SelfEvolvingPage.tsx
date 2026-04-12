import { type ReactElement, useDeferredValue, useEffect, useRef, useState } from 'react';

import { SelfEvolvingOverviewCards } from '../components/selfevolving/SelfEvolvingOverviewCards';
import { useSelfEvolvingPageState } from './SelfEvolvingPageHooks';
import { SelfEvolvingPageTabs, type ActiveTab } from './SelfEvolvingPageTabs';
import {
  SelfEvolvingCandidatesPanel,
  SelfEvolvingRunsPanel,
  SelfEvolvingTacticsPanel,
} from './SelfEvolvingPageTabPanels';
import {
  useDeactivateTactic,
  useReactivateTactic,
  useDeleteTactic,
  usePlanSelfEvolvingPromotion,
  useSelfEvolvingCandidates,
  useSelfEvolvingRunDetail,
  useSelfEvolvingRuns,
  useSelfEvolvingTacticSearch,
} from '../hooks/useSelfEvolving';
import { useRuntimeConfig } from '../hooks/useSettings';

export default function SelfEvolvingPage(): ReactElement {
  const [activeTab, setActiveTab] = useState<ActiveTab>('runs');
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [selectedCandidateId, setSelectedCandidateId] = useState<string | null>(null);
  const [tacticQuery, setTacticQuery] = useState<string>('');
  const [selectedTacticId, setSelectedTacticId] = useState<string | null>(null);
  const verdictPanelRef = useRef<HTMLDivElement | null>(null);
  const deferredTacticQuery = useDeferredValue(tacticQuery);
  const planPromotion = usePlanSelfEvolvingPromotion();
  const deactivateTactic = useDeactivateTactic();
  const reactivateTactic = useReactivateTactic();
  const deleteTactic = useDeleteTactic();

  const runsQuery = useSelfEvolvingRuns();
  const candidatesQuery = useSelfEvolvingCandidates();
  const tacticSearchQuery = useSelfEvolvingTacticSearch(deferredTacticQuery);
  const runtimeConfigQuery = useRuntimeConfig();
  const runs = runsQuery.data ?? [];
  const candidates = candidatesQuery.data ?? [];
  const {
    activeRunId,
    activeCandidateId,
    rolloutStages,
    handleSelectRunFromCandidate,
    handleTacticQueryChange,
  } = useSelfEvolvingPageState({
    selectedRunId,
    selectedCandidateId,
    runs,
    candidates,
    shadowRequired: runtimeConfigQuery.data?.selfEvolving.promotion.shadowRequired,
    canaryRequired: runtimeConfigQuery.data?.selfEvolving.promotion.canaryRequired,
    setSelectedRunId,
    setSelectedTacticId,
    setTacticQuery,
    setActiveTab,
  });

  const activeRunQuery = useSelfEvolvingRunDetail(activeRunId);

  const hasError = runsQuery.isError || candidatesQuery.isError;



  // Scroll verdict panel into view when a run is explicitly selected.
  useEffect(() => {
    if (selectedRunId == null) {
      return;
    }
    verdictPanelRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [selectedRunId]);

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <div>
          <h4 className="mb-1 text-lg font-semibold tracking-tight">Self-Evolving</h4>
          <p className="text-sm text-muted-foreground">
            Monitor how the agent learns from its runs and review concrete proposals generated from judged evidence.
          </p>
        </div>
      </div>

      {hasError && (
        <div className="alert mb-4 border-destructive/40 bg-destructive/10 text-destructive">
          Failed to load data. Refresh the page and check backend connectivity.
        </div>
      )}

      <SelfEvolvingOverviewCards runs={runs} candidates={candidates} />

      <SelfEvolvingPageTabs activeTab={activeTab} onSelectTab={setActiveTab} />

      {activeTab === 'runs' && (
        <SelfEvolvingRunsPanel
          runs={runs}
          activeRunId={activeRunId}
          activeRunQuery={activeRunQuery}
          verdictPanelRef={verdictPanelRef}
          onSelectRun={setSelectedRunId}
        />
      )}

      {activeTab === 'candidates' && (
        <SelfEvolvingCandidatesPanel
          candidates={candidates}
          activeCandidateId={activeCandidateId}
          rolloutStages={rolloutStages}
          planPromotion={planPromotion}
          onSelectCandidate={setSelectedCandidateId}
          onSelectRun={handleSelectRunFromCandidate}
        />
      )}

      {activeTab === 'tactics' && (
        <SelfEvolvingTacticsPanel
          tacticQuery={tacticQuery}
          tacticSearchResponse={tacticSearchQuery.data ?? null}
          selectedTacticId={selectedTacticId}
          onQueryChange={handleTacticQueryChange}
          onSelectTacticId={setSelectedTacticId}
          onDeactivateTactic={deactivateTactic.mutate}
          onReactivateTactic={reactivateTactic.mutate}
          onDeleteTactic={(tacticId) => deleteTactic.mutate(tacticId, { onSuccess: () => setSelectedTacticId(null) })}
          isDeactivatingTactic={deactivateTactic.isPending && deactivateTactic.variables === selectedTacticId}
          isReactivatingTactic={reactivateTactic.isPending && reactivateTactic.variables === selectedTacticId}
          isDeletingTactic={deleteTactic.isPending && deleteTactic.variables === selectedTacticId}
        />
      )}
    </div>
  );
}
