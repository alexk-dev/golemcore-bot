import type { ReactElement } from 'react';
import { Col, Row } from 'react-bootstrap';

import type {
  SelfEvolvingArtifactCatalogEntry,
  SelfEvolvingArtifactEvidence,
  SelfEvolvingArtifactLineage,
  SelfEvolvingArtifactRevisionDiff,
  SelfEvolvingArtifactTransitionDiff,
  SelfEvolvingArtifactWorkspaceSummary,
} from '../../api/selfEvolving';
import {
  SelfEvolvingArtifactCompareToolbar,
  type SelfEvolvingArtifactCompareMode,
} from './SelfEvolvingArtifactCompareToolbar';
import { SelfEvolvingArtifactBenchmarkImpactPanel } from './SelfEvolvingArtifactBenchmarkImpactPanel';
import { SelfEvolvingArtifactCatalog } from './SelfEvolvingArtifactCatalog';
import { SelfEvolvingArtifactDiffTabs } from './SelfEvolvingArtifactDiffTabs';
import { SelfEvolvingArtifactEvidencePanel } from './SelfEvolvingArtifactEvidencePanel';
import { SelfEvolvingArtifactLineageRail } from './SelfEvolvingArtifactLineageRail';

interface SelfEvolvingArtifactWorkspaceProps {
  artifacts: SelfEvolvingArtifactCatalogEntry[];
  selectedArtifactStreamId: string | null;
  workspaceSummary: SelfEvolvingArtifactWorkspaceSummary | null;
  lineage: SelfEvolvingArtifactLineage | null;
  compareMode: SelfEvolvingArtifactCompareMode;
  selectedFromRevisionId: string | null;
  selectedToRevisionId: string | null;
  selectedFromNodeId: string | null;
  selectedToNodeId: string | null;
  revisionDiff: SelfEvolvingArtifactRevisionDiff | null;
  transitionDiff: SelfEvolvingArtifactTransitionDiff | null;
  evidence: SelfEvolvingArtifactEvidence | null;
  isCatalogLoading: boolean;
  isWorkspaceLoading: boolean;
  isLineageLoading: boolean;
  isDiffLoading: boolean;
  isEvidenceLoading: boolean;
  onSelectArtifactStream: (artifactStreamId: string) => void;
  onSelectCompareMode: (compareMode: SelfEvolvingArtifactCompareMode) => void;
  onSelectRevisionPair: (fromRevisionId: string, toRevisionId: string) => void;
  onSelectTransitionPair: (fromNodeId: string, toNodeId: string) => void;
}

export function SelfEvolvingArtifactWorkspace({
  artifacts,
  selectedArtifactStreamId,
  workspaceSummary,
  lineage,
  compareMode,
  selectedFromRevisionId,
  selectedToRevisionId,
  selectedFromNodeId,
  selectedToNodeId,
  revisionDiff,
  transitionDiff,
  evidence,
  isCatalogLoading,
  isWorkspaceLoading,
  isLineageLoading,
  isDiffLoading,
  isEvidenceLoading,
  onSelectArtifactStream,
  onSelectCompareMode,
  onSelectRevisionPair,
  onSelectTransitionPair,
}: SelfEvolvingArtifactWorkspaceProps): ReactElement {
  const impactSummary = compareMode === 'transition'
    ? transitionDiff?.impactSummary ?? null
    : revisionDiff?.impactSummary ?? null;
  const compareOptions = workspaceSummary?.compareOptions ?? null;

  return (
    <Row className="g-3 mb-4">
      <Col xl={4} lg={12}>
        <SelfEvolvingArtifactCatalog
          artifacts={artifacts}
          isLoading={isCatalogLoading}
          selectedArtifactStreamId={selectedArtifactStreamId}
          onSelectArtifactStream={onSelectArtifactStream}
        />
      </Col>
      <Col xl={3} lg={6}>
        <div className="d-flex flex-column gap-3">
          <SelfEvolvingArtifactLineageRail
            lineage={lineage}
            isLoading={isLineageLoading || isWorkspaceLoading}
          />
          <SelfEvolvingArtifactCompareToolbar
            compareMode={compareMode}
            compareOptions={compareOptions}
            selectedFromRevisionId={selectedFromRevisionId}
            selectedToRevisionId={selectedToRevisionId}
            selectedFromNodeId={selectedFromNodeId}
            selectedToNodeId={selectedToNodeId}
            onSelectCompareMode={onSelectCompareMode}
            onSelectRevisionPair={onSelectRevisionPair}
            onSelectTransitionPair={onSelectTransitionPair}
          />
        </div>
      </Col>
      <Col xl={5} lg={6}>
        <div className="d-flex flex-column gap-3">
          <SelfEvolvingArtifactDiffTabs
            compareMode={compareMode}
            revisionDiff={revisionDiff}
            transitionDiff={transitionDiff}
            isLoading={isDiffLoading}
          />
          <SelfEvolvingArtifactEvidencePanel
            evidence={evidence}
            isLoading={isEvidenceLoading}
          />
          <SelfEvolvingArtifactBenchmarkImpactPanel impactSummary={impactSummary} />
        </div>
      </Col>
    </Row>
  );
}
