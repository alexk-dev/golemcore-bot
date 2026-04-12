import { type ReactElement, useState } from 'react';

import type { SelfEvolvingTacticSearchExplanation } from '../../api/selfEvolving';
import { formatPercent, formatNumber, formatList, humanizeStatus } from './selfEvolvingUi';

interface Props {
  explanation: SelfEvolvingTacticSearchExplanation | null;
  successRate?: number | null;
  benchmarkWinRate?: number | null;
  regressionFlags?: string[];
  promotionState?: string | null;
  recencyScore?: number | null;
  golemLocalUsageSuccess?: number | null;
}

function MetricRow({ label, value, hint }: { label: string; value: string; hint?: string }): ReactElement {
  return (
    <div className="flex items-center justify-between py-1.5 border-b border-border/40 last:border-b-0">
      <span className="text-xs text-muted-foreground" title={hint}>{label}</span>
      <span className="text-sm font-medium">{value}</span>
    </div>
  );
}

function ScoringDetails({
  explanation,
  golemLocalUsageSuccess,
}: { explanation: SelfEvolvingTacticSearchExplanation | null; golemLocalUsageSuccess?: number | null }): ReactElement {
  return (
    <div className="mt-3 pt-3 border-t border-border/60">
      <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground mb-2 block">Scoring details</span>
      <MetricRow label="Golem-local usage" value={formatPercent(golemLocalUsageSuccess)} hint="Success rate on this specific golem instance" />
      <MetricRow label="BM25 score" value={formatNumber(explanation?.bm25Score)} hint="Keyword-based retrieval score" />
      <MetricRow label="Vector score" value={formatNumber(explanation?.vectorScore)} hint="Semantic embedding similarity score" />
      <MetricRow label="RRF score" value={formatNumber(explanation?.rrfScore)} hint="Reciprocal Rank Fusion — combines BM25 and vector results" />
      <MetricRow label="Quality prior" value={formatNumber(explanation?.qualityPrior)} hint="Baseline quality bonus from historical performance" />
      <MetricRow label="MMR diversity" value={formatNumber(explanation?.mmrDiversityAdjustment)} hint="Adjustment to avoid duplicate-like results" />
      <MetricRow label="Memory penalty" value={formatNumber(explanation?.negativeMemoryPenalty)} hint="Penalty from negative memory entries about this tactic" />
      <MetricRow label="Personalization" value={formatNumber(explanation?.personalizationBoost)} hint="Boost based on this golem's usage patterns" />
      <MetricRow label="Matched views" value={formatList(explanation?.matchedQueryViews)} />
      <MetricRow label="Matched terms" value={formatList(explanation?.matchedTerms)} />
      <MetricRow label="Eligible" value={explanation?.eligible == null ? 'n/a' : explanation.eligible ? 'yes' : 'no'} hint="Whether this tactic passed gating checks" />
      <MetricRow label="Gating reason" value={explanation?.gatingReason ?? 'n/a'} hint="Reason this tactic was gated (if any)" />
    </div>
  );
}

export function SelfEvolvingTacticWhyPanel({
  explanation,
  successRate,
  benchmarkWinRate,
  regressionFlags = [],
  promotionState,
  recencyScore,
  golemLocalUsageSuccess,
}: Props): ReactElement {
  const [isExpanded, setIsExpanded] = useState<boolean>(false);

  return (
    <div className="card card-body">
      <h6 className="text-sm font-semibold mb-3">Why this tactic</h6>

      <MetricRow label="Success rate" value={formatPercent(successRate)} hint="Percentage of runs where this tactic produced a successful outcome" />
      <MetricRow label="Benchmark win rate" value={formatPercent(benchmarkWinRate)} hint="How often this tactic wins against alternatives in benchmark suites" />
      <MetricRow label="Promotion state" value={humanizeStatus(promotionState ?? null)} hint="Current lifecycle stage: candidate, active, or reverted" />
      <MetricRow label="Recency" value={formatNumber(recencyScore)} hint="Higher means the tactic was updated or used recently" />
      <MetricRow label="Final score" value={formatNumber(explanation?.finalScore)} hint="Combined relevance score that determines search ranking" />
      <MetricRow label="Regression flags" value={regressionFlags.join(', ') || 'none'} hint="Known regressions or risk signals detected for this tactic" />

      {isExpanded && <ScoringDetails explanation={explanation} golemLocalUsageSuccess={golemLocalUsageSuccess} />}

      <button
        type="button"
        className="mt-3 text-xs font-medium text-muted-foreground hover:text-foreground transition-colors"
        onClick={() => setIsExpanded(!isExpanded)}
      >
        {isExpanded ? 'Hide scoring details' : 'Show scoring details'}
      </button>
    </div>
  );
}
