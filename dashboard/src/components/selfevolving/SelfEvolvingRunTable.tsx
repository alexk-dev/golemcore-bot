import type { ReactElement } from 'react';
import { Badge, Button, Card, Table } from 'react-bootstrap';

import type { SelfEvolvingRunSummary } from '../../api/selfEvolving';

interface SelfEvolvingRunTableProps {
  runs: SelfEvolvingRunSummary[];
  selectedRunId: string | null;
  onSelectRun: (runId: string) => void;
}

function formatTimestamp(value: string | null): string {
  if (value == null) {
    return 'Pending';
  }
  return new Date(value).toLocaleString();
}

function toBadgeVariant(status: string | null): 'secondary' | 'success' | 'warning' | 'danger' | 'primary' {
  if (status === 'completed' || status === 'approve_gated') {
    return 'success';
  }
  if (status === 'failed' || status === 'rejected') {
    return 'danger';
  }
  if (status === 'shadowed' || status === 'approved_pending') {
    return 'warning';
  }
  if (status === 'running') {
    return 'primary';
  }
  return 'secondary';
}

export function SelfEvolvingRunTable({
  runs,
  selectedRunId,
  onSelectRun,
}: SelfEvolvingRunTableProps): ReactElement {
  return (
    <Card className="selfevolving-section-card">
      <Card.Body>
        <div className="d-flex align-items-center justify-content-between gap-2 mb-3">
          <div>
            <h5 className="mb-1">Recent Runs</h5>
            <p className="text-body-secondary small mb-0">Trace-backed run history with the latest outcome and promotion signal.</p>
          </div>
          <Badge bg="secondary">{runs.length}</Badge>
        </div>

        {runs.length === 0 ? (
          <div className="text-body-secondary small">No Self-Evolving runs have been captured yet.</div>
        ) : (
          <div className="table-responsive">
            <Table hover className="align-middle selfevolving-table mb-0">
              <thead>
                <tr>
                  <th>Run</th>
                  <th>Golem</th>
                  <th>Outcome</th>
                  <th>Recommendation</th>
                  <th>Started</th>
                  <th className="text-end">Action</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => (
                  <tr key={run.id} className={selectedRunId === run.id ? 'selfevolving-run-row active' : 'selfevolving-run-row'}>
                    <td>{run.id}</td>
                    <td>{run.golemId ?? 'n/a'}</td>
                    <td><Badge bg={toBadgeVariant(run.outcomeStatus)}>{run.outcomeStatus ?? 'unknown'}</Badge></td>
                    <td>{run.promotionRecommendation ?? 'n/a'}</td>
                    <td>{formatTimestamp(run.startedAt)}</td>
                    <td className="text-end">
                      <Button type="button" size="sm" variant="primary" onClick={() => onSelectRun(run.id)}>
                        Inspect
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
