import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { SelfEvolvingTacticSearchWorkspace } from './SelfEvolvingTacticSearchWorkspace';

const TACTIC_RESULT = {
  tacticId: 'planner',
  artifactStreamId: 'stream-1',
  originArtifactStreamId: 'origin-1',
  artifactKey: 'skill:planner',
  artifactType: 'skill',
  title: 'Planner tactic',
  aliases: ['planner'],
  contentRevisionId: 'rev-1',
  intentSummary: 'Plans complex tasks',
  behaviorSummary: 'Breaks work into ordered steps',
  toolSummary: 'filesystem, shell',
  outcomeSummary: 'Improves task completion',
  benchmarkSummary: 'Wins benchmark suite',
  approvalNotes: 'approved after canary',
  evidenceSnippets: ['trace:run-1', 'campaign:cmp-1'],
  taskFamilies: ['planning'],
  tags: ['core'],
  promotionState: 'active',
  rolloutStage: 'active',
  successRate: 0.92,
  benchmarkWinRate: 0.81,
  regressionFlags: ['none'],
  recencyScore: 0.78,
  golemLocalUsageSuccess: 0.88,
  embeddingStatus: 'indexed',
  updatedAt: '2026-04-01T23:59:00Z',
  score: 1.18,
  explanation: {
    searchMode: 'hybrid',
    bm25Score: 0.5,
    vectorScore: 0.4,
    rrfScore: 0.9,
    qualityPrior: 0.2,
    mmrDiversityAdjustment: -0.01,
    negativeMemoryPenalty: 0.0,
    personalizationBoost: 0.08,
    matchedQueryViews: ['planner'],
    matchedTerms: ['planner'],
    eligible: true,
    gatingReason: null,
    degradedReason: 'none',
    finalScore: 1.18,
  },
};

const SEARCH_STATUS = {
  mode: 'hybrid',
  reason: null,
  degraded: false,
  provider: 'ollama',
  model: 'qwen3-embedding:0.6b',
  runtimeInstalled: true,
  runtimeHealthy: true,
  runtimeVersion: '0.19.0',
  baseUrl: 'http://127.0.0.1:11434',
  modelAvailable: true,
  autoInstallConfigured: true,
  pullOnStartConfigured: true,
  pullAttempted: true,
  pullSucceeded: true,
  updatedAt: '2026-04-01T23:59:00Z',
};

describe('SelfEvolvingTacticSearchWorkspace', () => {
  it('renders compact tactic cards in browse mode', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTacticSearchWorkspace
        query="planner"
        onQueryChange={vi.fn()}
        onBackToResults={vi.fn()}
        onDeactivateTactic={vi.fn()}
        onReactivateTactic={vi.fn()}
        onDeleteTactic={vi.fn()}
        isDeactivatingTactic={false}
        isReactivatingTactic={false}
        isDeletingTactic={false}
        searchResponse={{ query: 'planner', status: SEARCH_STATUS, results: [TACTIC_RESULT] }}
        selectedTacticId={null}
        onSelectTacticId={vi.fn()}
      />,
    );

    expect(html).toContain('Tactic Search');
    expect(html).toContain('Hybrid');
    expect(html).toContain('name="tactic-search-query"');
    expect(html).toContain('Planner tactic');
    expect(html).toContain('Success');
    expect(html).toContain('Benchmark');
    expect(html).toContain('Open tactic');
    expect(html).not.toContain('Why this tactic');
  });

  it('renders the detailed tactic view after selection', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTacticSearchWorkspace
        query="planner"
        onQueryChange={vi.fn()}
        onBackToResults={vi.fn()}
        onDeactivateTactic={vi.fn()}
        onReactivateTactic={vi.fn()}
        onDeleteTactic={vi.fn()}
        isDeactivatingTactic={false}
        isReactivatingTactic={false}
        isDeletingTactic={false}
        searchResponse={{ query: 'planner', status: SEARCH_STATUS, results: [TACTIC_RESULT] }}
        selectedTacticId="planner"
        onSelectTacticId={vi.fn()}
      />,
    );

    expect(html).toContain('Planner tactic');
    expect(html).toContain('Back to results');
    expect(html).toContain('Why this tactic');
    expect(html).toContain('Final score');
    expect(html).toContain('Evidence');
    expect(html).toContain('Intent');
    expect(html).toContain('Behavior');
    expect(html).toContain('Deactivate');
    expect(html).toContain('Delete');
  });

  it('renders n/a only for tactic runtime metrics while keeping search score numeric', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTacticSearchWorkspace
        query="planner"
        onQueryChange={vi.fn()}
        onBackToResults={vi.fn()}
        onDeactivateTactic={vi.fn()}
        onReactivateTactic={vi.fn()}
        onDeleteTactic={vi.fn()}
        isDeactivatingTactic={false}
        isReactivatingTactic={false}
        isDeletingTactic={false}
        searchResponse={{
          query: 'planner',
          status: SEARCH_STATUS,
          results: [
            {
              ...TACTIC_RESULT,
              successRate: null,
              benchmarkWinRate: null,
              recencyScore: null,
              golemLocalUsageSuccess: null,
              score: 1.18,
              explanation: {
                ...TACTIC_RESULT.explanation,
                finalScore: 1.18,
              },
            },
          ],
        }}
        selectedTacticId={null}
        onSelectTacticId={vi.fn()}
      />,
    );

    expect(html).toContain('Success n/a');
    expect(html).toContain('Benchmark n/a');
    expect(html).toContain('Score 1.18');
  });
});
