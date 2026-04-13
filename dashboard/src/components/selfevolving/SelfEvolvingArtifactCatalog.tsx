import type { ReactElement } from 'react';
import { Badge, Button, Card, ListGroup } from '../ui/tailwind-components';

import type { SelfEvolvingArtifactCatalogEntry } from '../../api/selfEvolving';

interface SelfEvolvingArtifactCatalogProps {
  artifacts: SelfEvolvingArtifactCatalogEntry[];
  isLoading: boolean;
  selectedArtifactStreamId: string | null;
  onSelectArtifactStream: (artifactStreamId: string) => void;
}

function buildArtifactLabel(artifact: SelfEvolvingArtifactCatalogEntry): string {
  return artifact.displayName ?? artifact.artifactKey ?? artifact.artifactStreamId;
}

export function SelfEvolvingArtifactCatalog({
  artifacts,
  isLoading,
  selectedArtifactStreamId,
  onSelectArtifactStream,
}: SelfEvolvingArtifactCatalogProps): ReactElement {
  return (
    <Card className="selfevolving-section-card">
      <Card.Body>
        <div className="d-flex align-items-center justify-content-between gap-2 mb-3">
          <div>
            <h5 className="mb-1">Artifact Catalog</h5>
            <p className="text-body-secondary small mb-0">All evolved streams for the active golem, grouped by canonical artifact identity.</p>
          </div>
          <Badge bg="secondary">{artifacts.length}</Badge>
        </div>

        {isLoading ? (
          <div className="text-body-secondary small">Loading artifact streams...</div>
        ) : artifacts.length === 0 ? (
          <div className="text-body-secondary small">No evolved artifact streams are available yet.</div>
        ) : (
          <ListGroup variant="flush" className="selfevolving-artifact-catalog">
            {artifacts.map((artifact) => {
              const isSelected = artifact.artifactStreamId === selectedArtifactStreamId;
              return (
                <ListGroup.Item key={artifact.artifactStreamId} className="px-0 py-2 border-0">
                  <Button
                    type="button"
                    variant={isSelected ? 'primary' : 'secondary'}
                    className="w-100 text-start selfevolving-artifact-catalog-item"
                    onClick={() => onSelectArtifactStream(artifact.artifactStreamId)}
                  >
                    <div className="d-flex align-items-center justify-content-between gap-2">
                      <div className="min-w-0">
                        <div className="fw-semibold text-truncate">{buildArtifactLabel(artifact)}</div>
                        <div className="small text-body-secondary text-truncate">
                          {artifact.artifactType ?? 'artifact'} / {artifact.currentRolloutStage ?? 'unknown'}
                        </div>
                      </div>
                      <Badge bg={artifact.hasPendingApproval ? 'warning' : 'dark'}>
                        {artifact.latestCandidateRevisionId ?? artifact.activeRevisionId ?? 'n/a'}
                      </Badge>
                    </div>
                  </Button>
                </ListGroup.Item>
              );
            })}
          </ListGroup>
        )}
      </Card.Body>
    </Card>
  );
}
