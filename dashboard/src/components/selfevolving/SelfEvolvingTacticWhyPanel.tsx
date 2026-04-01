import type { ReactElement } from 'react';
import { Card, ListGroup } from 'react-bootstrap';

import type { SelfEvolvingTacticSearchExplanation } from '../../api/selfEvolving';

interface Props {
  explanation: SelfEvolvingTacticSearchExplanation | null;
  successRate?: number | null;
  benchmarkWinRate?: number | null;
  regressionFlags?: string[];
  promotionState?: string | null;
  recencyScore?: number | null;
  golemLocalUsageSuccess?: number | null;
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
  return (
    <Card className="mb-3">
      <Card.Body>
        <Card.Title className="h6">Why this tactic</Card.Title>
      </Card.Body>
      <ListGroup variant="flush">
        <ListGroup.Item>Success rate: {formatPercent(successRate)}</ListGroup.Item>
        <ListGroup.Item>Benchmark win rate: {formatPercent(benchmarkWinRate)}</ListGroup.Item>
        <ListGroup.Item>Regression flags: {regressionFlags.join(', ') || 'none'}</ListGroup.Item>
        <ListGroup.Item>Promotion state: {promotionState ?? 'n/a'}</ListGroup.Item>
        <ListGroup.Item>Recency: {formatNumber(recencyScore)}</ListGroup.Item>
        <ListGroup.Item>Golem-local usage success: {formatPercent(golemLocalUsageSuccess)}</ListGroup.Item>
        <ListGroup.Item>BM25 score: {formatNumber(explanation?.bm25Score)}</ListGroup.Item>
        <ListGroup.Item>Vector score: {formatNumber(explanation?.vectorScore)}</ListGroup.Item>
        <ListGroup.Item>RRF score: {formatNumber(explanation?.rrfScore)}</ListGroup.Item>
        <ListGroup.Item>Quality prior: {formatNumber(explanation?.qualityPrior)}</ListGroup.Item>
        <ListGroup.Item>MMR diversity adjustment: {formatNumber(explanation?.mmrDiversityAdjustment)}</ListGroup.Item>
        <ListGroup.Item>Negative memory penalty: {formatNumber(explanation?.negativeMemoryPenalty)}</ListGroup.Item>
        <ListGroup.Item>Personalization boost: {formatNumber(explanation?.personalizationBoost)}</ListGroup.Item>
        <ListGroup.Item>Reranker verdict: {explanation?.rerankerVerdict ?? 'n/a'}</ListGroup.Item>
        <ListGroup.Item>Matched query views: {formatList(explanation?.matchedQueryViews)}</ListGroup.Item>
        <ListGroup.Item>Matched terms: {formatList(explanation?.matchedTerms)}</ListGroup.Item>
        <ListGroup.Item>Eligible: {formatBoolean(explanation?.eligible)}</ListGroup.Item>
        <ListGroup.Item>Gating reason: {explanation?.gatingReason ?? 'n/a'}</ListGroup.Item>
        <ListGroup.Item>Degradation reason: {explanation?.degradedReason ?? 'n/a'}</ListGroup.Item>
        <ListGroup.Item>Final score: {formatNumber(explanation?.finalScore)}</ListGroup.Item>
      </ListGroup>
    </Card>
  );
}

function formatPercent(value: number | null | undefined): string {
  return value == null ? 'n/a' : `${Math.round(value * 100)}%`;
}

function formatNumber(value: number | null | undefined): string {
  return value == null ? 'n/a' : value.toFixed(2);
}

function formatList(values: string[] | null | undefined): string {
  return values == null || values.length === 0 ? 'n/a' : values.join(', ');
}

function formatBoolean(value: boolean | null | undefined): string {
  if (value == null) {
    return 'n/a';
  }
  return value ? 'yes' : 'no';
}
