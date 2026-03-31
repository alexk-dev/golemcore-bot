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
  }),
  useSelfEvolvingCampaigns: () => ({
    data: [
      {
        id: 'campaign-1',
        suiteId: 'suite-1',
        baselineBundleId: 'bundle-1',
        candidateBundleId: 'bundle-2',
        status: 'created',
        startedAt: '2026-03-31T12:03:00Z',
        completedAt: null,
        runIds: ['run-1'],
      },
    ],
    isLoading: false,
  }),
  usePlanSelfEvolvingPromotion: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
    variables: null,
  }),
  useCreateSelfEvolvingRegressionCampaign: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
  }),
}));

describe('SelfEvolvingPage', () => {
  it('renders run, candidate, and benchmark sections', () => {
    const html = renderToStaticMarkup(<SelfEvolvingPage />);

    expect(html).toContain('Candidate Queue');
    expect(html).toContain('Benchmark Lab');
    expect(html).toContain('Recent Runs');
  });
});
