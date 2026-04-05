import { type ReactElement, useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';

import { SelfEvolvingCandidateQueue } from '../components/selfevolving/SelfEvolvingCandidateQueue';
import { SelfEvolvingOverviewCards } from '../components/selfevolving/SelfEvolvingOverviewCards';
import { SelfEvolvingRunTable } from '../components/selfevolving/SelfEvolvingRunTable';
import { SelfEvolvingTacticSearchWorkspace } from '../components/selfevolving/SelfEvolvingTacticSearchWorkspace';
import { SelfEvolvingVerdictPanel } from '../components/selfevolving/SelfEvolvingVerdictPanel';
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

type ActiveTab = 'runs' | 'candidates' | 'tactics';

const TAB_LABELS: Record<ActiveTab, { label: string; hint: string }> = {
  runs: { label: 'Runs', hint: 'Every agent session captured and judged by the self-evolving pipeline' },
  candidates: { label: 'Candidates', hint: 'Concrete proposals derived from judged runs, waiting for review or activation' },
  tactics: { label: 'Tactics', hint: 'Search the tactic library used by the agent at runtime' },
};

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
  const rolloutStages = useMemo(
    () => ({
      shadowRequired: runtimeConfigQuery.data?.selfEvolving.promotion.shadowRequired ?? true,
      canaryRequired: runtimeConfigQuery.data?.selfEvolving.promotion.canaryRequired ?? true,
    }),
    [runtimeConfigQuery.data],
  );

  const runs = runsQuery.data ?? [];
  const candidates = candidatesQuery.data ?? [];
  const activeRunId = selectedRunId ?? runs[0]?.id ?? null;
  const activeCandidateId = selectedCandidateId ?? candidates[0]?.id ?? null;
  const activeRunQuery = useSelfEvolvingRunDetail(activeRunId);

  const hasError = runsQuery.isError || candidatesQuery.isError;

  const handlePlanPromotion = (candidateId: string): void => {
    planPromotion.mutate(candidateId);
  };

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

      <div className="flex gap-1 border-b border-border/80 mb-4" role="tablist">
        {(Object.keys(TAB_LABELS) as ActiveTab[]).map((tab) => (
          <button
            key={tab}
            type="button"
            role="tab"
            aria-selected={activeTab === tab}
            title={TAB_LABELS[tab].hint}
            className={`px-4 py-2.5 text-sm font-semibold transition-colors border-b-2 -mb-px ${
              activeTab === tab
                ? 'border-primary text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground hover:border-border'
            }`}
            onClick={() => setActiveTab(tab)}
          >
            {TAB_LABELS[tab].label}
          </button>
        ))}
      </div>

      {activeTab === 'runs' && (
        <div className="grid grid-cols-1 xl:grid-cols-12 gap-4">
          <div className="xl:col-span-7">
            <SelfEvolvingRunTable
              runs={runs}
              selectedRunId={activeRunId}
              onSelectRun={setSelectedRunId}
            />
          </div>
          <div className="xl:col-span-5" ref={verdictPanelRef}>
            <SelfEvolvingVerdictPanel
              run={activeRunQuery.data}
              isLoading={activeRunQuery.isLoading}
            />
          </div>
        </div>
      )}

      {activeTab === 'candidates' && (
        <SelfEvolvingCandidateQueue
          candidates={candidates}
          selectedCandidateId={activeCandidateId}
          promotingCandidateId={planPromotion.isPending ? (planPromotion.variables ?? null) : null}
          lastPromotionResult={planPromotion.data ?? null}
          lastPromotionError={planPromotion.isError}
          shadowRequired={rolloutStages.shadowRequired}
          canaryRequired={rolloutStages.canaryRequired}
          onSelectCandidate={setSelectedCandidateId}
          onSelectRun={(runId) => {
            setSelectedRunId(runId);
            setActiveTab('runs');
          }}
          onPlanPromotion={handlePlanPromotion}
        />
      )}

      {activeTab === 'tactics' && (
        <SelfEvolvingTacticSearchWorkspace
          query={tacticQuery}
          onQueryChange={(nextQuery) => {
            setTacticQuery(nextQuery);
            setSelectedTacticId(null);
          }}
          searchResponse={tacticSearchQuery.data ?? null}
          selectedTacticId={selectedTacticId}
          onSelectTacticId={setSelectedTacticId}
          onBackToResults={() => setSelectedTacticId(null)}
          onDeactivateTactic={(tacticId) => {
            deactivateTactic.mutate(tacticId);
          }}
          onReactivateTactic={(tacticId) => {
            reactivateTactic.mutate(tacticId);
          }}
          onDeleteTactic={(tacticId) => {
            deleteTactic.mutate(tacticId, {
              onSuccess: () => setSelectedTacticId(null),
            });
          }}
          isDeactivatingTactic={deactivateTactic.isPending && deactivateTactic.variables === selectedTacticId}
          isReactivatingTactic={reactivateTactic.isPending && reactivateTactic.variables === selectedTacticId}
          isDeletingTactic={deleteTactic.isPending && deleteTactic.variables === selectedTacticId}
        />
      )}
    </div>
  );
}
