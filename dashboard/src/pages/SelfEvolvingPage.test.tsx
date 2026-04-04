import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import SelfEvolvingPage from './SelfEvolvingPage';

vi.mock('../hooks/useSelfEvolving', () => ({
  useSelfEvolvingRuns: () => ({
    data: [
      {
        id: 'run-1',
        golemId: 'golem-1',
        sessionId: 'session-1',
        traceId: 'trace-1',
        artifactBundleId: 'bundle-1',
        status: 'completed',
        outcomeStatus: 'completed',
        promotionRecommendation: 'approve_gated',
        startedAt: '2026-03-31T12:00:00Z',
        completedAt: '2026-03-31T12:02:00Z',
      },
    ],
    isLoading: false,
    isError: false,
  }),
  useSelfEvolvingRunDetail: () => ({
    data: {
      id: 'run-1',
      golemId: 'golem-1',
      sessionId: 'session-1',
      traceId: 'trace-1',
      artifactBundleId: 'bundle-1',
      artifactBundleStatus: 'active',
      status: 'completed',
      startedAt: '2026-03-31T12:00:00Z',
      completedAt: '2026-03-31T12:02:00Z',
      verdict: {
        outcomeStatus: 'completed',
        processStatus: 'healthy',
        outcomeSummary: 'Task completed.',
        processSummary: 'Routing was efficient.',
        promotionRecommendation: 'approve_gated',
        confidence: 0.91,
        processFindings: ['Tier escalation was not needed.'],
      },
    },
    isLoading: false,
    isError: false,
  }),
  useSelfEvolvingCandidates: () => ({
    data: [
      {
        id: 'candidate-1',
        goal: 'fix',
        artifactType: 'skill',
        status: 'approved_pending',
        riskLevel: 'medium',
        expectedImpact: 'Reduce routing failures',
        sourceRunIds: ['run-1'],
      },
    ],
    isLoading: false,
    isError: false,
  }),
  usePlanSelfEvolvingPromotion: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
    variables: null,
  }),
  useSelfEvolvingTacticSearch: () => ({
    data: null,
    isLoading: false,
    isError: false,
  }),
}));

describe('SelfEvolvingPage', () => {
  it('renders the overview cards and runs tab by default', () => {
    const html = renderToStaticMarkup(<SelfEvolvingPage />);

    // Header and overview
    expect(html).toContain('Self-Evolving');
    expect(html).toContain('Tracked Runs');
    expect(html).toContain('Pending Promotions');

    // Tab navigation
    expect(html).toContain('Runs');
    expect(html).toContain('Candidates');
    expect(html).toContain('Tactics');

    // Runs tab content (active by default)
    expect(html).toContain('Recent Runs');
    expect(html).toContain('run-1');
    expect(html).toContain('Run run-1');
    expect(html).toContain('Verdict Panel');

    // Other tabs not rendered
    expect(html).not.toContain('Candidate Queue');
    expect(html).not.toContain('Tactic Search');
  });
});
