import type { ReactElement } from 'react';
import { Badge, Button, Card, Table } from '../ui/tailwind-components';

import type { SelfEvolvingCampaign } from '../../api/selfEvolving';

interface SelfEvolvingBenchmarkLabProps {
  campaigns: SelfEvolvingCampaign[];
  selectedRunId: string | null;
  isCreatingCampaign: boolean;
  onCreateRegressionCampaign: () => void;
}

function formatTimestamp(value: string | null): string {
  if (value == null) {
    return 'Pending';
  }
  return new Date(value).toLocaleString();
}

export function SelfEvolvingBenchmarkLab({
  campaigns,
  selectedRunId,
  isCreatingCampaign,
  onCreateRegressionCampaign,
}: SelfEvolvingBenchmarkLabProps): ReactElement {
  return (
    <Card className="selfevolving-section-card">
      <Card.Body>
        <div className="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3">
          <div>
            <h5 className="mb-1">Benchmark Lab</h5>
            <p className="text-body-secondary small mb-0">Harvest production runs into reproducible regression campaigns.</p>
          </div>
          <Button
            type="button"
            size="sm"
            variant="primary"
            onClick={onCreateRegressionCampaign}
            disabled={selectedRunId == null || isCreatingCampaign}
          >
            {isCreatingCampaign ? 'Creating...' : 'Create regression campaign'}
          </Button>
        </div>

        {selectedRunId != null && (
          <div className="small text-body-secondary mb-3">
            Source run: {selectedRunId}
          </div>
        )}

        {campaigns.length === 0 ? (
          <div className="text-body-secondary small">No benchmark campaigns have been created yet.</div>
        ) : (
          <div className="table-responsive">
            <Table hover className="align-middle selfevolving-table mb-0">
              <thead>
                <tr>
                  <th>Campaign</th>
                  <th>Status</th>
                  <th>Baseline</th>
                  <th>Candidate</th>
                  <th>Started</th>
                </tr>
              </thead>
              <tbody>
                {campaigns.map((campaign) => (
                  <tr key={campaign.id}>
                    <td>
                      <div>{campaign.id}</div>
                      <div className="small text-body-secondary">Suite {campaign.suiteId ?? 'n/a'}</div>
                    </td>
                    <td><Badge bg="secondary">{campaign.status ?? 'unknown'}</Badge></td>
                    <td>{campaign.baselineBundleId ?? 'n/a'}</td>
                    <td>{campaign.candidateBundleId ?? 'n/a'}</td>
                    <td>{formatTimestamp(campaign.startedAt)}</td>
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
