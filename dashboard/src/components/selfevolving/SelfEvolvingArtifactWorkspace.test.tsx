import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { SelfEvolvingArtifactWorkspace } from './SelfEvolvingArtifactWorkspace';

describe('SelfEvolvingArtifactWorkspace', () => {
  it('renders artifact catalog, lineage rail, and diff tabs for the selected stream', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingArtifactWorkspace
        artifacts={[
          {
            artifactStreamId: 'stream-1',
            originArtifactStreamId: 'stream-1',
            artifactKey: 'skill:planner',
            artifactAliases: ['skill:planner'],
            artifactType: 'skill',
            artifactSubtype: 'skill',
            displayName: 'skill:planner',
            latestRevisionId: 'rev-2',
            activeRevisionId: 'rev-1',
            latestCandidateRevisionId: 'rev-2',
            currentLifecycleState: 'candidate',
            currentRolloutStage: 'canary',
            hasRegression: false,
            hasPendingApproval: true,
            campaignCount: 1,
            projectionSchemaVersion: 1,
            updatedAt: '2026-03-31T12:02:00Z',
            projectedAt: '2026-03-31T12:02:00Z',
          },
        ]}
        selectedArtifactStreamId="stream-1"
        workspaceSummary={{
          artifactStreamId: 'stream-1',
          originArtifactStreamId: 'stream-1',
          artifactKey: 'skill:planner',
          artifactAliases: ['skill:planner'],
          artifactType: 'skill',
          artifactSubtype: 'skill',
          activeRevisionId: 'rev-1',
          latestCandidateRevisionId: 'rev-2',
          currentLifecycleState: 'candidate',
          currentRolloutStage: 'canary',
          campaignCount: 1,
          projectionSchemaVersion: 1,
          updatedAt: '2026-03-31T12:02:00Z',
          projectedAt: '2026-03-31T12:02:00Z',
          compareOptions: {
            artifactStreamId: 'stream-1',
            defaultFromRevisionId: 'rev-1',
            defaultToRevisionId: 'rev-2',
            defaultFromNodeId: 'candidate-1:proposed',
            defaultToNodeId: 'decision-1:shadowed',
            revisionOptions: [{ label: 'active_vs_candidate', fromId: 'rev-1', toId: 'rev-2' }],
            transitionOptions: [{ label: 'transition_1', fromId: 'candidate-1:proposed', toId: 'decision-1:shadowed' }],
          },
        }}
        lineage={{
          artifactStreamId: 'stream-1',
          originArtifactStreamId: 'stream-1',
          artifactKey: 'skill:planner',
          nodes: [
            {
              nodeId: 'candidate-1:proposed',
              contentRevisionId: 'rev-2',
              lifecycleState: 'candidate',
              rolloutStage: 'proposed',
              promotionDecisionId: null,
              originBundleId: 'bundle-1',
              sourceRunIds: ['run-1'],
              campaignIds: ['campaign-1'],
              attributionMode: 'bundle_observed',
              createdAt: '2026-03-31T12:01:00Z',
            },
          ],
          edges: [],
          railOrder: ['candidate-1:proposed'],
          branches: [],
          defaultSelectedNodeId: 'candidate-1:proposed',
          defaultSelectedRevisionId: 'rev-2',
          projectionSchemaVersion: 1,
          projectedAt: '2026-03-31T12:02:00Z',
        }}
        compareMode="revision"
        selectedFromRevisionId="rev-1"
        selectedToRevisionId="rev-2"
        selectedFromNodeId="candidate-1:proposed"
        selectedToNodeId="decision-1:shadowed"
        revisionDiff={{
          artifactStreamId: 'stream-1',
          artifactKey: 'skill:planner',
          fromRevisionId: 'rev-1',
          toRevisionId: 'rev-2',
          summary: 'Artifact content changed',
          semanticSections: ['planner'],
          rawPatch: '--- from\\nplanner v1\\n+++ to\\nplanner v2',
          changedFields: ['normalizedContent'],
          riskSignals: ['content_changed'],
          impactSummary: {
            attributionMode: 'isolated',
            campaignDelta: 1,
            regressionIntroduced: false,
            verdictDelta: null,
            latencyDeltaMs: null,
            costDeltaMicros: null,
            projectionSchemaVersion: 1,
            projectedAt: '2026-03-31T12:02:00Z',
          },
          attributionMode: 'isolated',
          projectionSchemaVersion: 1,
          projectedAt: '2026-03-31T12:02:00Z',
        }}
        transitionDiff={null}
        evidence={{
          artifactStreamId: 'stream-1',
          artifactKey: 'skill:planner',
          payloadKind: 'revision',
          revisionId: 'rev-2',
          fromRevisionId: null,
          toRevisionId: null,
          fromNodeId: null,
          toNodeId: null,
          runIds: ['run-1'],
          traceIds: [],
          spanIds: [],
          campaignIds: ['campaign-1'],
          promotionDecisionIds: ['decision-1'],
          approvalRequestIds: ['approval-1'],
          findings: ['revision_evidence'],
          projectionSchemaVersion: 1,
          projectedAt: '2026-03-31T12:02:00Z',
        }}
        isCatalogLoading={false}
        isWorkspaceLoading={false}
        isLineageLoading={false}
        isDiffLoading={false}
        isEvidenceLoading={false}
        onSelectArtifactStream={vi.fn()}
        onSelectCompareMode={vi.fn()}
        onSelectRevisionPair={vi.fn()}
        onSelectTransitionPair={vi.fn()}
      />,
    );

    expect(html).toContain('Artifact Catalog');
    expect(html).toContain('Lineage Rail');
    expect(html).toContain('Semantic diff');
    expect(html).toContain('skill:planner');
  });
});
