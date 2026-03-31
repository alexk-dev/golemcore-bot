import type { ReactElement } from 'react';
import { Badge, Button, Card, Table } from 'react-bootstrap';

import type { SelfEvolvingCandidate } from '../../api/selfEvolving';

interface SelfEvolvingCandidateQueueProps {
  candidates: SelfEvolvingCandidate[];
  promotingCandidateId: string | null;
  onPlanPromotion: (candidateId: string) => void;
}

export function SelfEvolvingCandidateQueue({
  candidates,
  promotingCandidateId,
  onPlanPromotion,
}: SelfEvolvingCandidateQueueProps): ReactElement {
  return (
    <Card className="selfevolving-section-card">
      <Card.Body>
        <div className="d-flex align-items-center justify-content-between gap-2 mb-3">
          <div>
            <h5 className="mb-1">Candidate Queue</h5>
            <p className="text-body-secondary small mb-0">Generated `fix`, `derive`, and `tune` candidates waiting for rollout decisions.</p>
          </div>
          <Badge bg="secondary">{candidates.length}</Badge>
        </div>

        {candidates.length === 0 ? (
          <div className="text-body-secondary small">No evolution candidates are queued.</div>
        ) : (
          <div className="table-responsive">
            <Table hover className="align-middle selfevolving-table mb-0">
              <thead>
                <tr>
                  <th>Candidate</th>
                  <th>Type</th>
                  <th>Status</th>
                  <th>Impact</th>
                  <th className="text-end">Action</th>
                </tr>
              </thead>
              <tbody>
                {candidates.map((candidate) => (
                  <tr key={candidate.id}>
                    <td>
                      <div>{candidate.id}</div>
                      <div className="small text-body-secondary">{candidate.goal ?? 'unknown goal'}</div>
                    </td>
                    <td>{candidate.artifactType ?? 'n/a'}</td>
                    <td>{candidate.status ?? 'unknown'}</td>
                    <td>{candidate.expectedImpact ?? 'n/a'}</td>
                    <td className="text-end">
                      <Button
                        type="button"
                        size="sm"
                        variant="secondary"
                        onClick={() => onPlanPromotion(candidate.id)}
                        disabled={promotingCandidateId === candidate.id}
                      >
                        {promotingCandidateId === candidate.id ? 'Planning...' : 'Plan promotion'}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </div>
        )}
      </Card.Body>
    </Card>
  );
}
