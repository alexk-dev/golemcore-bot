import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { SelfEvolvingTacticWhyPanel } from './SelfEvolvingTacticWhyPanel';

describe('SelfEvolvingTacticWhyPanel', () => {
  it('renders all quality prior badges and ranking adjustments', () => {
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

    expect(html).toContain('Success rate');
    expect(html).toContain('Benchmark win rate');
    expect(html).toContain('Regression flags');
    expect(html).toContain('Golem-local usage success');
    expect(html).toContain('MMR diversity adjustment');
    expect(html).toContain('Negative memory penalty');
    expect(html).toContain('Matched query views');
    expect(html).toContain('Matched terms');
    expect(html).toContain('Eligible');
    expect(html).toContain('Gating reason');
    expect(html).toContain('Degradation reason');
  });
});
