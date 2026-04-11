import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { SelfEvolvingCandidateQueue } from './SelfEvolvingCandidateQueue';
import { createCandidateFixture } from './selfEvolvingCandidateQueueTestFixtures';

function renderQueue(candidates: ReturnType<typeof createCandidateFixture>[], selectedCandidateId: string): string {
  return renderToStaticMarkup(
    <SelfEvolvingCandidateQueue
      candidates={candidates}
      selectedCandidateId={selectedCandidateId}
      promotingCandidateId={null}
      lastPromotionResult={null}
      lastPromotionErrorCandidateId={null}
      onSelectCandidate={vi.fn()}
      onSelectRun={vi.fn()}
      onPlanPromotion={vi.fn()}
    />,
  );
}

describe('SelfEvolvingCandidateQueue', () => {
  it('renders proposal, evidence, and diff for the selected candidate', () => {
    const html = renderQueue([
      createCandidateFixture({
        id: '51728b76-26a0-4e27-a759-4db742624fc8',
        artifactType: 'tool_policy',
        artifactKey: 'tool_policy:shell',
        riskLevel: 'medium',
        expectedImpact: 'Reduce the failure mode observed in this run',
        proposedDiff: '--- a/tool_policy.yaml\n+++ b/tool_policy.yaml\n-allow: [shell]\n+allow: [shell, browser]',
        proposal: {
          summary: 'Harden tool usage policy after missing binary failure',
          rationale: 'The run retried a missing shell command instead of replanning.',
          behaviorInstructions: 'Check tool availability before shell execution.',
          toolInstructions: 'Use `command -v` before invoking shell tools.',
          expectedOutcome: 'Reduce repeated missing binary failures.',
          approvalNotes: 'Anchored to trace-1/skill-1.',
          proposedPatch: '--- a/tool_policy.yaml\n+++ b/tool_policy.yaml\n-allow: [shell]\n+allow: [shell, browser]',
          riskLevel: 'medium',
        },
        sourceRunIds: ['run-1', 'run-2'],
        evidenceRefs: [
          { traceId: 'trace-1', spanId: 'skill-1', outputFragment: 'Browser tool was blocked by policy' },
        ],
      }),
    ], '51728b76-26a0-4e27-a759-4db742624fc8');

    expect(html).toContain('What is proposed');
    expect(html).toContain('Why this change');
    expect(html).toContain('Proposed behavior');
    expect(html).toContain('Tooling guidance');
    expect(html).toContain('tool_policy:shell');
    expect(html).toContain('Proposed patch');
    expect(html).toContain('allow: [shell, browser]');
    expect(html).toContain('What the judge observed');
    expect(html).toContain('Browser tool was blocked by policy');
    expect(html).toContain('Approve rollout');
  });

  it('renders evidence when no real diff is available', () => {
    const html = renderQueue([
      createCandidateFixture({
        id: 'cand-2',
        goal: 'derive',
        proposedDiff: 'selfevolving:derive:skill',
        proposal: {
          summary: 'Capture the successful planner tactic as reusable guidance',
          rationale: 'The run completed cleanly with a stable planning sequence.',
          behaviorInstructions: 'Reuse the planner sequence when the task requires decomposition.',
          toolInstructions: 'Prefer planning before tool execution on multi-step tasks.',
          expectedOutcome: 'Improve planning accuracy.',
          approvalNotes: 'Anchored to trace-2/planner.',
          proposedPatch: null,
          riskLevel: 'low',
        },
        sourceRunIds: ['run-3'],
        evidenceRefs: [
          { traceId: 'trace-2', spanId: 'planner', outputFragment: 'Planner produced suboptimal step ordering' },
        ],
      }),
    ], 'cand-2');

    expect(html).not.toContain('Proposed patch');
    expect(html).toContain('Capture the successful planner tactic as reusable guidance');
    expect(html).toContain('What the judge observed');
    expect(html).toContain('Planner produced suboptimal step ordering');
  });

  it('shows promotion result after successful promotion', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingCandidateQueue
        candidates={[createCandidateFixture({ id: 'cand-3', status: 'active', expectedImpact: 'Fix routing' })]}
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
        lastPromotionErrorCandidateId={null}
        onSelectCandidate={vi.fn()}
        onSelectRun={vi.fn()}
        onPlanPromotion={vi.fn()}
      />,
    );

    expect(html).toContain('No proposed changes yet');
    expect(html).not.toContain('Approve rollout');
  });

  it('hides already active candidates from the proposed changes list', () => {
    const html = renderQueue([
      createCandidateFixture({
        id: 'cand-active',
        status: 'active',
        expectedImpact: 'Already live',
        proposal: {
          summary: 'Live tactic',
          rationale: null,
          behaviorInstructions: 'Keep current behavior.',
          toolInstructions: null,
          expectedOutcome: 'Stay live.',
          approvalNotes: null,
          proposedPatch: null,
          riskLevel: 'low',
        },
      }),
      createCandidateFixture({
        id: 'cand-review',
        artifactType: 'tool_policy',
        artifactKey: 'tool_policy:shell',
        riskLevel: 'medium',
        expectedImpact: 'Reviewable change',
        proposal: {
          summary: 'Reviewable candidate',
          rationale: 'This one should stay visible.',
          behaviorInstructions: 'Inspect before rollout.',
          toolInstructions: null,
          expectedOutcome: 'Safer command execution.',
          approvalNotes: 'Anchored to trace-review/span-review.',
          proposedPatch: null,
          riskLevel: 'medium',
        },
        sourceRunIds: ['run-review'],
      }),
    ], 'cand-active');

    expect(html).toContain('Reviewable candidate');
    expect(html).toContain('Approve rollout');
    expect(html).not.toContain('Live tactic');
    expect(html).toContain('text-bg-secondary">1<');
  });

  it('shows a promotion error only for the failed candidate', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingCandidateQueue
        candidates={[
          createCandidateFixture({ id: 'cand-selected', expectedImpact: 'Selected candidate' }),
          createCandidateFixture({ id: 'cand-failed', riskLevel: 'medium', expectedImpact: 'Failed candidate' }),
        ]}
        selectedCandidateId="cand-selected"
        promotingCandidateId={null}
        lastPromotionResult={null}
        lastPromotionErrorCandidateId="cand-failed"
        onSelectCandidate={vi.fn()}
        onSelectRun={vi.fn()}
        onPlanPromotion={vi.fn()}
      />,
    );

    expect(html).not.toContain('Promotion failed. Check backend logs for details.');
  });
});
