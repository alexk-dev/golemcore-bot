import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { SelfEvolvingTacticWhyPanel } from './SelfEvolvingTacticWhyPanel';

describe('SelfEvolvingTacticWhyPanel', () => {
  it('renders key metrics and a toggle for scoring details', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTacticWhyPanel
        explanation={{
          searchMode: 'hybrid',
          bm25Score: 0.5,
          vectorScore: 0.4,
          rrfScore: 0.9,
          qualityPrior: 0.2,
          mmrDiversityAdjustment: -0.01,
          negativeMemoryPenalty: 0.05,
          personalizationBoost: 0.08,
          rerankerVerdict: 'tier deep via gpt-5.4/high',
          matchedQueryViews: ['planner'],
          matchedTerms: ['planner', 'shell'],
          eligible: false,
          gatingReason: 'candidate_only',
          degradedReason: 'Embeddings degraded to BM25-only',
          finalScore: 1.12,
        }}
        successRate={0.92}
        benchmarkWinRate={0.81}
        regressionFlags={['regression-risk']}
        promotionState="active"
        recencyScore={0.78}
        golemLocalUsageSuccess={0.88}
      />,
    );

    // Key metrics always visible
    expect(html).toContain('Success rate');
    expect(html).toContain('Benchmark win rate');
    expect(html).toContain('Promotion state');
    expect(html).toContain('Recency');
    expect(html).toContain('Final score');
    expect(html).toContain('Regression flags');

    // Scoring details collapsed by default
    expect(html).toContain('Show scoring details');
    expect(html).not.toContain('Golem-local usage');
    expect(html).not.toContain('MMR diversity');
    expect(html).not.toContain('Matched views');
  });
});
