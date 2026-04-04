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
            status: 'proposed',
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
    expect(html).toContain('Why this change');
    expect(html).toContain('Proposed behavior');
    expect(html).toContain('Tooling guidance');
    expect(html).toContain('tool_policy:shell');
    expect(html).toContain('Proposed patch');
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
            status: 'proposed',
            riskLevel: 'low',
            expectedImpact: 'Improve planning accuracy',
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
    expect(html).toContain('Capture the successful planner tactic as reusable guidance');
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
            proposal: {
              summary: 'Capture the successful planner tactic as reusable guidance',
              rationale: null,
              behaviorInstructions: 'Reuse the planner sequence when the task requires decomposition.',
              toolInstructions: 'Prefer planning before tool execution on multi-step tasks.',
              expectedOutcome: 'Improve planning accuracy.',
              approvalNotes: 'Already active.',
              proposedPatch: null,
              riskLevel: 'low',
            },
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
