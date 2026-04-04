import type { ReactElement } from 'react';

import type { SelfEvolvingTacticSearchResult } from '../../api/selfEvolving';
import { formatPercent, formatNumber } from './selfEvolvingUi';

interface Props {
  results: SelfEvolvingTacticSearchResult[];
  selectedTacticId: string | null;
  onSelectTacticId: (tacticId: string) => void;
}

export function SelfEvolvingTacticResultsList({ results, selectedTacticId, onSelectTacticId }: Props): ReactElement {
  if (results.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">No results. Try a different search term.</p>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {results.map((result) => {
        const isSelected = selectedTacticId === result.tacticId;
        return (
          <button
            key={result.tacticId}
            type="button"
            className={`w-full text-left rounded-xl border p-3 transition-colors ${
              isSelected
                ? 'border-primary/50 bg-primary/5'
                : 'border-border/60 hover:border-border hover:bg-muted/30'
            }`}
            onClick={() => onSelectTacticId(result.tacticId)}
          >
            <div className="flex justify-between items-start gap-2">
              <div className="min-w-0">
                <div className="font-semibold text-sm">{result.title ?? result.tacticId}</div>
                {result.artifactKey != null && (
                  <div className="text-xs text-muted-foreground font-mono">{result.artifactKey}</div>
                )}
                <p className="text-xs text-muted-foreground mt-1 mb-0 line-clamp-2">
                  {result.intentSummary ?? result.outcomeSummary ?? 'No summary available.'}
                </p>
              </div>
              <span className="text-xs font-medium text-primary shrink-0">
                {isSelected ? 'Selected' : 'Open tactic'}
              </span>
            </div>
            <div className="flex flex-wrap gap-1.5 mt-2">
              <span className="badge text-bg-secondary" title="Lifecycle state of this tactic">
                {result.promotionState ?? 'n/a'}
              </span>
              <span className="badge text-bg-secondary" title="Percentage of runs where this tactic succeeded">
                Success {formatPercent(result.successRate)}
              </span>
              <span className="badge text-bg-secondary" title="Win rate against alternative tactics in benchmarks">
                Benchmark {formatPercent(result.benchmarkWinRate)}
              </span>
              <span className="badge text-bg-secondary" title="Overall relevance score for this query">
                Score {formatNumber(result.score)}
              </span>
            </div>
          </button>
        );
      })}
    </div>
  );
}
