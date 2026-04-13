import type { ReactElement } from 'react';
import { Badge, Card, ListGroup } from '../ui/tailwind-components';

import type { SelfEvolvingArtifactLineage } from '../../api/selfEvolving';

interface SelfEvolvingArtifactLineageRailProps {
  lineage: SelfEvolvingArtifactLineage | null;
  isLoading: boolean;
}

export function SelfEvolvingArtifactLineageRail({
  lineage,
  isLoading,
}: SelfEvolvingArtifactLineageRailProps): ReactElement {
  return (
    <Card className="selfevolving-section-card">
      <Card.Body>
        <div className="d-flex align-items-center justify-content-between gap-2 mb-3">
          <div>
            <h5 className="mb-1">Lineage Rail</h5>
            <p className="text-body-secondary small mb-0">Ordered rollout milestones from candidate proposal through activation and revert.</p>
          </div>
          <Badge bg="secondary">{lineage?.railOrder.length ?? 0}</Badge>
        </div>

        {isLoading ? (
          <div className="text-body-secondary small">Loading lineage...</div>
        ) : lineage == null || lineage.nodes.length === 0 ? (
          <div className="text-body-secondary small">Select an artifact stream to inspect its rollout history.</div>
        ) : (
          <ListGroup variant="flush" className="selfevolving-lineage-rail">
            {lineage.railOrder.map((nodeId) => {
              const node = lineage.nodes.find((candidateNode) => candidateNode.nodeId === nodeId);
              if (node == null) {
                return null;
              }
              return (
                <ListGroup.Item key={node.nodeId} className="px-0 py-2 border-0">
                  <div className="selfevolving-lineage-node">
                    <div className="d-flex align-items-center justify-content-between gap-2">
                      <div className="fw-semibold">{node.rolloutStage ?? node.nodeId}</div>
                      <Badge bg="dark">{node.contentRevisionId ?? 'n/a'}</Badge>
                    </div>
                    <div className="small text-body-secondary">
                      {node.lifecycleState ?? 'unknown'} · {node.attributionMode ?? 'unclassified'}
                    </div>
                  </div>
                </ListGroup.Item>
              );
            })}
          </ListGroup>
        )}
      </Card.Body>
    </Card>
  );
}
