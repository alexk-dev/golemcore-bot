import type { ReactElement } from 'react';

import type { SelfEvolvingTacticSearchResponse } from '../../api/selfEvolving';
import { SelfEvolvingTacticDetailPanel } from './SelfEvolvingTacticDetailPanel';
import { SelfEvolvingTacticResultsList } from './SelfEvolvingTacticResultsList';
import { SelfEvolvingTacticSearchStatusBanner } from './SelfEvolvingTacticSearchStatusBanner';
import { SelfEvolvingTacticWhyPanel } from './SelfEvolvingTacticWhyPanel';

interface Props {
  query: string;
  onQueryChange: (query: string) => void;
  searchResponse: SelfEvolvingTacticSearchResponse | null;
  selectedTacticId: string | null;
  onSelectTacticId: (tacticId: string) => void;
  onBackToResults: () => void;
  onDeactivateTactic: (tacticId: string) => void;
  onDeleteTactic: (tacticId: string) => void;
  isDeactivatingTactic: boolean;
  isDeletingTactic: boolean;
}

export function SelfEvolvingTacticSearchWorkspace({
  query,
  onQueryChange,
  searchResponse,
  selectedTacticId,
  onSelectTacticId,
  onBackToResults,
  onDeactivateTactic,
  onDeleteTactic,
  isDeactivatingTactic,
  isDeletingTactic,
}: Props): ReactElement {
  const results = searchResponse?.results ?? [];
  const selected = results.find((result) => result.tacticId === selectedTacticId) ?? results[0] ?? null;
  const isDetailMode = selectedTacticId != null && selected != null;

  return (
    <div className="card card-body">
      {!isDetailMode && (
        <>
          <h5 className="text-base font-semibold tracking-tight mb-1">Tactic Search</h5>
          <p className="text-xs text-muted-foreground mb-3">
            Search the tactic library the agent uses at runtime. Type a task description or keyword.
          </p>
          <input
            name="tactic-search-query"
            type="search"
            className="form-control mb-3"
            value={query}
            placeholder="e.g. planner, tool routing, failure recovery"
            onChange={(event) => onQueryChange(event.currentTarget.value)}
          />
          <SelfEvolvingTacticSearchStatusBanner status={searchResponse?.status ?? null} />
          <SelfEvolvingTacticResultsList
            results={results}
            selectedTacticId={selectedTacticId}
            onSelectTacticId={onSelectTacticId}
          />
        </>
      )}

      {isDetailMode && (
        <>
          <div className="flex flex-wrap items-center justify-between gap-2 mb-4">
            <h5 className="text-base font-semibold tracking-tight mb-0">{selected.title ?? selected.tacticId}</h5>
            <button type="button" className="btn btn-sm btn-secondary" onClick={onBackToResults}>
              Back to results
            </button>
          </div>
          <div className="grid grid-cols-1 xl:grid-cols-12 gap-4">
            <div className="xl:col-span-5">
              <SelfEvolvingTacticDetailPanel
                tactic={selected}
                onDeactivateTactic={onDeactivateTactic}
                onDeleteTactic={onDeleteTactic}
                isDeactivating={isDeactivatingTactic}
                isDeleting={isDeletingTactic}
              />
            </div>
            <div className="xl:col-span-7">
              <SelfEvolvingTacticWhyPanel
                explanation={selected.explanation ?? null}
                successRate={selected.successRate}
                benchmarkWinRate={selected.benchmarkWinRate}
                regressionFlags={selected.regressionFlags}
                promotionState={selected.promotionState}
                recencyScore={selected.recencyScore}
                golemLocalUsageSuccess={selected.golemLocalUsageSuccess}
              />
            </div>
          </div>
        </>
      )}
    </div>
  );
}
