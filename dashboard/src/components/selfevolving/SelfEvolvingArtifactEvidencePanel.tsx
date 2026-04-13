import type { ReactElement } from 'react';
import { Card } from '../ui/tailwind-components';

import type { SelfEvolvingArtifactEvidence } from '../../api/selfEvolving';

interface SelfEvolvingArtifactEvidencePanelProps {
  evidence: SelfEvolvingArtifactEvidence | null;
  isLoading: boolean;
}

function renderList(values: string[]): ReactElement {
  if (values.length === 0) {
    return <div className="small text-body-secondary">None</div>;
  }
  return (
    <ul className="mb-0 ps-3">
      {values.map((value) => (
        <li key={value}>{value}</li>
      ))}
    </ul>
  );
}

export function SelfEvolvingArtifactEvidencePanel({
  evidence,
  isLoading,
}: SelfEvolvingArtifactEvidencePanelProps): ReactElement {
  return (
    <Card className="selfevolving-section-card">
      <Card.Body>
        <div className="mb-3">
          <h5 className="mb-1">Evidence</h5>
          <p className="text-body-secondary small mb-0">Runs, campaigns, approvals, and findings that justify the selected compare pair.</p>
        </div>

        {isLoading ? (
          <div className="text-body-secondary small">Loading evidence...</div>
        ) : evidence == null ? (
          <div className="text-body-secondary small">No evidence available for the current selection.</div>
        ) : (
          <div className="d-flex flex-column gap-3">
            <div className="selfevolving-verdict-block">
              <div className="small text-uppercase text-body-secondary mb-1">Run IDs</div>
              {renderList(evidence.runIds)}
            </div>
            <div className="selfevolving-verdict-block">
              <div className="small text-uppercase text-body-secondary mb-1">Campaign IDs</div>
              {renderList(evidence.campaignIds)}
            </div>
            <div className="selfevolving-verdict-block">
              <div className="small text-uppercase text-body-secondary mb-1">Findings</div>
              {renderList(evidence.findings)}
            </div>
          </div>
        )}
      </Card.Body>
    </Card>
  );
}
