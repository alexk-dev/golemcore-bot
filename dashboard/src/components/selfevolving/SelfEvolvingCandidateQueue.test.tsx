import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { SelfEvolvingCandidateQueue } from './SelfEvolvingCandidateQueue';

describe('SelfEvolvingCandidateQueue', () => {
  it('renders proposal, evidence, and diff for the selected candidate', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingCandidateQueue
        candidates={[
          {
            id: '51728b76-26a0-4e27-a759-4db742624fc8',
            goal: 'fix',
            artifactType: 'tool_policy',
            artifactKey: 'tool_policy:shell',
            status: 'approved_pending',
            riskLevel: 'medium',
            expectedImpact: 'Reduce the failure mode observed in this run',
            proposedDiff: '--- a/tool_policy.yaml\n+++ b/tool_policy.yaml\n-allow: [shell]\n+allow: [shell, browser]',
            sourceRunIds: ['run-1', 'run-2'],
            evidenceRefs: [
              { traceId: 'trace-1', spanId: 'skill-1', outputFragment: 'Browser tool was blocked by policy' },
            ],
          },
        ]}
        selectedCandidateId="51728b76-26a0-4e27-a759-4db742624fc8"
        promotingCandidateId={null}
        lastPromotionResult={null}
        lastPromotionError={false}
        onSelectCandidate={vi.fn()}
        onSelectRun={vi.fn()}
        onPlanPromotion={vi.fn()}
      />,
    );

    expect(html).toContain('What is proposed');
    expect(html).toContain('tool_policy:shell');
    expect(html).toContain('Proposed diff');
    expect(html).toContain('allow: [shell, browser]');
    expect(html).toContain('What the judge observed');
    expect(html).toContain('Browser tool was blocked by policy');
    expect(html).toContain('Approve and activate');
  });

  it('renders evidence when no real diff is available', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingCandidateQueue
        candidates={[
          {
            id: 'cand-2',
            goal: 'derive',
            artifactType: 'skill',
            status: 'approved_pending',
            riskLevel: 'low',
            expectedImpact: 'Improve planning accuracy',
            proposedDiff: 'selfevolving:derive:skill',
            sourceRunIds: ['run-3'],
            evidenceRefs: [
              { traceId: 'trace-2', spanId: 'planner', outputFragment: 'Planner produced suboptimal step ordering' },
            ],
          },
        ]}
        selectedCandidateId="cand-2"
        promotingCandidateId={null}
        lastPromotionResult={null}
        lastPromotionError={false}
        onSelectCandidate={vi.fn()}
        onSelectRun={vi.fn()}
        onPlanPromotion={vi.fn()}
      />,
    );

    expect(html).not.toContain('Proposed diff');
    expect(html).toContain('What the judge observed');
    expect(html).toContain('Planner produced suboptimal step ordering');
  });

  it('shows promotion result after successful promotion', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingCandidateQueue
        candidates={[
          {
            id: 'cand-3',
            goal: 'fix',
            artifactType: 'skill',
            status: 'active',
            riskLevel: 'low',
            expectedImpact: 'Fix routing',
            proposedDiff: null,
            sourceRunIds: [],
            evidenceRefs: [],
          },
        ]}
        selectedCandidateId="cand-3"
        promotingCandidateId={null}
        lastPromotionResult={{
          id: 'dec-1',
          candidateId: 'cand-3',
          bundleId: 'bundle-1',
          state: 'active',
          fromState: 'proposed',
          toState: 'active',
          mode: 'approval_gate',
          approvalRequestId: null,
          actorId: null,
          reason: 'Approved and activated as tactic',
          decidedAt: '2026-04-04T12:00:00Z',
        }}
        lastPromotionError={false}
        onSelectCandidate={vi.fn()}
        onSelectRun={vi.fn()}
        onPlanPromotion={vi.fn()}
      />,
    );

    expect(html).toContain('Change approved and activated');
    expect(html).toContain('Active');
    expect(html).toContain('serving all traffic');
    expect(html).not.toContain('Approve and activate');
  });
});
