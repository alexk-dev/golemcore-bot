import { type ReactElement, useDeferredValue, useState } from 'react';
import { Alert, Col, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';

import { SelfEvolvingArtifactWorkspace } from '../components/selfevolving/SelfEvolvingArtifactWorkspace';
import { SelfEvolvingBenchmarkLab } from '../components/selfevolving/SelfEvolvingBenchmarkLab';
import { SelfEvolvingCandidateQueue } from '../components/selfevolving/SelfEvolvingCandidateQueue';
import { SelfEvolvingOverviewCards } from '../components/selfevolving/SelfEvolvingOverviewCards';
import { SelfEvolvingRunTable } from '../components/selfevolving/SelfEvolvingRunTable';
import { SelfEvolvingTacticSearchWorkspace } from '../components/selfevolving/SelfEvolvingTacticSearchWorkspace';
import { SelfEvolvingVerdictPanel } from '../components/selfevolving/SelfEvolvingVerdictPanel';
import {
  useSelfEvolvingArtifacts,
  useSelfEvolvingArtifactEvidence,
  useSelfEvolvingArtifactLineage,
  useSelfEvolvingArtifactRevisionDiff,
  useSelfEvolvingArtifactTransitionDiff,
  useSelfEvolvingArtifactWorkspaceSummary,
  useCreateSelfEvolvingRegressionCampaign,
  usePlanSelfEvolvingPromotion,
  useSelfEvolvingCampaigns,
  useSelfEvolvingCandidates,
  useSelfEvolvingRunDetail,
  useSelfEvolvingRuns,
  useSelfEvolvingTacticSearch,
} from '../hooks/useSelfEvolving';

interface ArtifactSelectionState {
  artifactStreamId: string | null;
  compareMode: 'revision' | 'transition';
  revisionPair: {
    fromRevisionId: string | null;
    toRevisionId: string | null;
  };
  transitionPair: {
    fromNodeId: string | null;
    toNodeId: string | null;
  };
}

export default function SelfEvolvingPage(): ReactElement {
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [tacticQuery, setTacticQuery] = useState<string>('planner');
  const [selectedTacticId, setSelectedTacticId] = useState<string | null>(null);
  const deferredTacticQuery = useDeferredValue(tacticQuery);
  const [artifactSelection, setArtifactSelection] = useState<ArtifactSelectionState>({
    artifactStreamId: null,
    compareMode: 'revision',
    revisionPair: {
      fromRevisionId: null,
      toRevisionId: null,
    },
    transitionPair: {
      fromNodeId: null,
      toNodeId: null,
    },
  });
  const runsQuery = useSelfEvolvingRuns();
  const candidatesQuery = useSelfEvolvingCandidates();
  const campaignsQuery = useSelfEvolvingCampaigns();
  const artifactsQuery = useSelfEvolvingArtifacts();
  const planPromotion = usePlanSelfEvolvingPromotion();
  const createRegressionCampaign = useCreateSelfEvolvingRegressionCampaign();

  const runs = runsQuery.data ?? [];
  const candidates = candidatesQuery.data ?? [];
  const campaigns = campaignsQuery.data ?? [];
  const artifacts = artifactsQuery.data ?? [];
  const tacticSearchQuery = useSelfEvolvingTacticSearch(deferredTacticQuery);
  const activeRunId = selectedRunId ?? runs[0]?.id ?? null;
  const activeArtifactStreamId = artifactSelection.artifactStreamId ?? artifacts[0]?.artifactStreamId ?? null;
  const activeRunQuery = useSelfEvolvingRunDetail(activeRunId);
  const workspaceSummaryQuery = useSelfEvolvingArtifactWorkspaceSummary(activeArtifactStreamId);
  const lineageQuery = useSelfEvolvingArtifactLineage(activeArtifactStreamId);

  const activeCompareOptions = workspaceSummaryQuery.data?.compareOptions ?? null;
  const activeFromRevisionId = artifactSelection.revisionPair.fromRevisionId ?? activeCompareOptions?.defaultFromRevisionId ?? null;
  const activeToRevisionId = artifactSelection.revisionPair.toRevisionId ?? activeCompareOptions?.defaultToRevisionId ?? null;
  const activeFromNodeId = artifactSelection.transitionPair.fromNodeId ?? activeCompareOptions?.defaultFromNodeId ?? null;
  const activeToNodeId = artifactSelection.transitionPair.toNodeId ?? activeCompareOptions?.defaultToNodeId ?? null;

  const revisionDiffQuery = useSelfEvolvingArtifactRevisionDiff(
    activeArtifactStreamId,
    activeFromRevisionId,
    activeToRevisionId,
  );
  const transitionDiffQuery = useSelfEvolvingArtifactTransitionDiff(
    activeArtifactStreamId,
    activeFromNodeId,
    activeToNodeId,
  );
  const evidenceQuery = useSelfEvolvingArtifactEvidence(
    artifactSelection.compareMode,
    activeArtifactStreamId,
    activeToRevisionId,
    activeFromNodeId,
    activeToNodeId,
  );

  const handlePlanPromotion = async (candidateId: string): Promise<void> => {
    await planPromotion.mutateAsync(candidateId);
    toast.success('Promotion decision planned');
  };

  const handleCreateRegressionCampaign = async (): Promise<void> => {
    if (activeRunId == null) {
      return;
    }
    await createRegressionCampaign.mutateAsync(activeRunId);
    toast.success('Regression campaign created');
  };

  const handleSelectArtifactStream = (artifactStreamId: string): void => {
    setArtifactSelection((currentSelection) => ({
      ...currentSelection,
      artifactStreamId,
      revisionPair: {
        fromRevisionId: null,
        toRevisionId: null,
      },
      transitionPair: {
        fromNodeId: null,
        toNodeId: null,
      },
    }));
  };

  const handleSelectCompareMode = (compareMode: 'revision' | 'transition'): void => {
    setArtifactSelection((currentSelection) => ({
      ...currentSelection,
      compareMode,
    }));
  };

  const handleSelectRevisionPair = (fromRevisionId: string, toRevisionId: string): void => {
    setArtifactSelection((currentSelection) => ({
      ...currentSelection,
      compareMode: 'revision',
      revisionPair: {
        fromRevisionId,
        toRevisionId,
      },
    }));
  };

  const handleSelectTransitionPair = (fromNodeId: string, toNodeId: string): void => {
    setArtifactSelection((currentSelection) => ({
      ...currentSelection,
      compareMode: 'transition',
      transitionPair: {
        fromNodeId,
        toNodeId,
      },
    }));
  };

  const hasError = runsQuery.isError
    || candidatesQuery.isError
    || campaignsQuery.isError
    || artifactsQuery.isError
    || workspaceSummaryQuery.isError
    || lineageQuery.isError
    || revisionDiffQuery.isError
    || transitionDiffQuery.isError
    || evidenceQuery.isError;

  return (
    <div>
      <div className="section-header d-flex align-items-center justify-content-between">
        <div>
          <h4 className="mb-1">SelfEvolving</h4>
          <p className="text-body-secondary mb-0">
            Inspect runs, judge outcomes, queue promotions, and create benchmark campaigns for the active golem.
          </p>
        </div>
      </div>

      {hasError && (
        <Alert variant="danger" className="mb-4">
          Failed to load one or more SelfEvolving datasets. Refresh the page and check backend connectivity.
        </Alert>
      )}

      <SelfEvolvingOverviewCards runs={runs} candidates={candidates} campaigns={campaigns} />

      <SelfEvolvingTacticSearchWorkspace
        query={tacticQuery}
        onQueryChange={(nextQuery) => {
          setTacticQuery(nextQuery);
          setSelectedTacticId(null);
        }}
        searchResponse={tacticSearchQuery.data ?? null}
        selectedTacticId={selectedTacticId}
        onSelectTacticId={(tacticId) => {
          setSelectedTacticId(tacticId);
          const tactic = tacticSearchQuery.data?.results.find((result) => result.tacticId === tacticId);
          if (tactic?.artifactStreamId != null) {
            handleSelectArtifactStream(tactic.artifactStreamId);
          }
        }}
        onOpenArtifactStream={handleSelectArtifactStream}
      />

      <div id="self-evolving-artifact-workspace">
        <SelfEvolvingArtifactWorkspace
          artifacts={artifacts}
          selectedArtifactStreamId={activeArtifactStreamId}
          workspaceSummary={workspaceSummaryQuery.data ?? null}
          lineage={lineageQuery.data ?? null}
          compareMode={artifactSelection.compareMode}
          selectedFromRevisionId={activeFromRevisionId}
          selectedToRevisionId={activeToRevisionId}
          selectedFromNodeId={activeFromNodeId}
          selectedToNodeId={activeToNodeId}
          revisionDiff={revisionDiffQuery.data ?? null}
          transitionDiff={transitionDiffQuery.data ?? null}
          evidence={evidenceQuery.data ?? null}
          isCatalogLoading={artifactsQuery.isLoading}
          isWorkspaceLoading={workspaceSummaryQuery.isLoading}
          isLineageLoading={lineageQuery.isLoading}
          isDiffLoading={artifactSelection.compareMode === 'transition' ? transitionDiffQuery.isLoading : revisionDiffQuery.isLoading}
          isEvidenceLoading={evidenceQuery.isLoading}
          onSelectArtifactStream={handleSelectArtifactStream}
          onSelectCompareMode={handleSelectCompareMode}
          onSelectRevisionPair={handleSelectRevisionPair}
          onSelectTransitionPair={handleSelectTransitionPair}
        />
      </div>

      <Row className="g-3 mb-3">
        <Col xl={7}>
          <SelfEvolvingRunTable
            runs={runs}
            selectedRunId={activeRunId}
            onSelectRun={setSelectedRunId}
          />
        </Col>
        <Col xl={5}>
          <SelfEvolvingVerdictPanel
            run={activeRunQuery.data}
            isLoading={activeRunQuery.isLoading}
          />
        </Col>
      </Row>

      <Row className="g-3">
        <Col xl={6}>
          <SelfEvolvingCandidateQueue
            candidates={candidates}
            promotingCandidateId={planPromotion.variables ?? null}
            onPlanPromotion={(candidateId) => { void handlePlanPromotion(candidateId); }}
          />
        </Col>
        <Col xl={6}>
          <SelfEvolvingBenchmarkLab
            campaigns={campaigns}
            selectedRunId={activeRunId}
            isCreatingCampaign={createRegressionCampaign.isPending}
            onCreateRegressionCampaign={() => { void handleCreateRegressionCampaign(); }}
          />
        </Col>
      </Row>
    </div>
  );
}
