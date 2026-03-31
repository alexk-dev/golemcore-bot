import type { ReactElement } from 'react';
import { Card } from 'react-bootstrap';

import type { SelfEvolvingArtifactImpactSummary } from '../../api/selfEvolving';

interface SelfEvolvingArtifactBenchmarkImpactPanelProps {
  impactSummary: SelfEvolvingArtifactImpactSummary | null;
}

export function SelfEvolvingArtifactBenchmarkImpactPanel({
  impactSummary,
}: SelfEvolvingArtifactBenchmarkImpactPanelProps): ReactElement {
  return (
    <Card className="selfevolving-section-card">
      <Card.Body>
        <div className="mb-3">
          <h5 className="mb-1">Benchmark impact</h5>
          <p className="text-body-secondary small mb-0">Attribution mode and benchmark deltas for the selected artifact compare.</p>
        </div>

        {impactSummary == null ? (
          <div className="text-body-secondary small">No benchmark impact is available for the current selection.</div>
        ) : (
          <div className="d-flex flex-column gap-3">
            <div className="selfevolving-verdict-block">
              <div className="small text-uppercase text-body-secondary mb-1">Attribution mode</div>
              <div>{impactSummary.attributionMode ?? 'unknown'}</div>
            </div>
            <div className="selfevolving-verdict-block">
              <div className="small text-uppercase text-body-secondary mb-1">Campaign delta</div>
              <div>{impactSummary.campaignDelta ?? 0}</div>
            </div>
            <div className="selfevolving-verdict-block">
              <div className="small text-uppercase text-body-secondary mb-1">Regression introduced</div>
              <div>{impactSummary.regressionIntroduced ? 'Yes' : 'No'}</div>
            </div>
          </div>
        )}
      </Card.Body>
    </Card>
  );
}
