import type { SelfEvolvingCandidate } from '../../api/selfEvolving';

export function createCandidateFixture(overrides: Partial<SelfEvolvingCandidate>): SelfEvolvingCandidate {
  return {
    id: 'candidate-fixture',
    goal: 'fix',
    artifactType: 'skill',
    artifactKey: null,
    status: 'proposed',
    riskLevel: 'low',
    expectedImpact: 'Improve planning accuracy',
    proposedDiff: null,
    proposal: {
      summary: 'Capture the successful planner tactic as reusable guidance',
      rationale: null,
      behaviorInstructions: 'Reuse the planner sequence when the task requires decomposition.',
      toolInstructions: null,
      expectedOutcome: 'Improve planning accuracy.',
      approvalNotes: null,
      proposedPatch: null,
      riskLevel: 'low',
    },
    sourceRunIds: [],
    evidenceRefs: [],
    ...overrides,
  };
}
