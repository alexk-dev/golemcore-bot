import { type ReactElement, useState } from 'react';
import { Alert, Col, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';

import { SelfEvolvingBenchmarkLab } from '../components/selfevolving/SelfEvolvingBenchmarkLab';
import { SelfEvolvingCandidateQueue } from '../components/selfevolving/SelfEvolvingCandidateQueue';
import { SelfEvolvingOverviewCards } from '../components/selfevolving/SelfEvolvingOverviewCards';
import { SelfEvolvingRunTable } from '../components/selfevolving/SelfEvolvingRunTable';
import { SelfEvolvingVerdictPanel } from '../components/selfevolving/SelfEvolvingVerdictPanel';
import {
  useCreateSelfEvolvingRegressionCampaign,
  usePlanSelfEvolvingPromotion,
  useSelfEvolvingCampaigns,
  useSelfEvolvingCandidates,
  useSelfEvolvingRunDetail,
  useSelfEvolvingRuns,
} from '../hooks/useSelfEvolving';

export default function SelfEvolvingPage(): ReactElement {
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const runsQuery = useSelfEvolvingRuns();
  const candidatesQuery = useSelfEvolvingCandidates();
  const campaignsQuery = useSelfEvolvingCampaigns();
  const planPromotion = usePlanSelfEvolvingPromotion();
  const createRegressionCampaign = useCreateSelfEvolvingRegressionCampaign();

  const runs = runsQuery.data ?? [];
  const candidates = candidatesQuery.data ?? [];
  const campaigns = campaignsQuery.data ?? [];
  const activeRunId = selectedRunId ?? runs[0]?.id ?? null;
  const activeRunQuery = useSelfEvolvingRunDetail(activeRunId);

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

  const hasError = runsQuery.isError || candidatesQuery.isError || campaignsQuery.isError;

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
