import type { ReactElement } from 'react';
import { Card, Col, Row } from 'react-bootstrap';

import type {
  SelfEvolvingCampaign,
  SelfEvolvingCandidate,
  SelfEvolvingRunSummary,
} from '../../api/selfEvolving';

interface SelfEvolvingOverviewCardsProps {
  runs: SelfEvolvingRunSummary[];
  candidates: SelfEvolvingCandidate[];
  campaigns: SelfEvolvingCampaign[];
}

function countCompletedRuns(runs: SelfEvolvingRunSummary[]): number {
  return runs.filter((run) => run.outcomeStatus === 'completed' || run.status === 'completed').length;
}

function countPendingCandidates(candidates: SelfEvolvingCandidate[]): number {
  return candidates.filter((candidate) => candidate.status === 'approved_pending').length;
}

function countOpenCampaigns(campaigns: SelfEvolvingCampaign[]): number {
  return campaigns.filter((campaign) => campaign.status !== 'completed').length;
}

export function SelfEvolvingOverviewCards({
  runs,
  candidates,
  campaigns,
}: SelfEvolvingOverviewCardsProps): ReactElement {
  return (
    <Row className="g-3 mb-4">
      <Col sm={6} xl={3}>
        <Card className="stat-card selfevolving-overview-card">
          <Card.Body>
            <div className="text-body-secondary small">Tracked Runs</div>
            <h3>{runs.length}</h3>
          </Card.Body>
        </Card>
      </Col>
      <Col sm={6} xl={3}>
        <Card className="stat-card selfevolving-overview-card">
          <Card.Body>
            <div className="text-body-secondary small">Completed Runs</div>
            <h3>{countCompletedRuns(runs)}</h3>
          </Card.Body>
        </Card>
      </Col>
      <Col sm={6} xl={3}>
        <Card className="stat-card selfevolving-overview-card">
          <Card.Body>
            <div className="text-body-secondary small">Pending Promotions</div>
            <h3>{countPendingCandidates(candidates)}</h3>
          </Card.Body>
        </Card>
      </Col>
      <Col sm={6} xl={3}>
        <Card className="stat-card selfevolving-overview-card">
          <Card.Body>
            <div className="text-body-secondary small">Open Campaigns</div>
            <h3>{countOpenCampaigns(campaigns)}</h3>
          </Card.Body>
        </Card>
      </Col>
    </Row>
  );
}
