import type { ReactElement } from 'react';
import { Badge, Card, Placeholder } from 'react-bootstrap';

import type { SelfEvolvingRunDetail } from '../../api/selfEvolving';

interface SelfEvolvingVerdictPanelProps {
  run: SelfEvolvingRunDetail | undefined;
  isLoading: boolean;
}

function toBadgeVariant(status: string | null): 'secondary' | 'success' | 'warning' | 'danger' {
  if (status === 'completed' || status === 'healthy') {
    return 'success';
  }
  if (status === 'failed' || status === 'regression') {
    return 'danger';
  }
  if (status === 'shadowed' || status === 'degraded') {
    return 'warning';
  }
  return 'secondary';
}

export function SelfEvolvingVerdictPanel({
  run,
  isLoading,
}: SelfEvolvingVerdictPanelProps): ReactElement {
  if (isLoading) {
    return (
      <Card className="selfevolving-section-card">
        <Card.Body>
          <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={8} /></Placeholder>
          <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={12} /></Placeholder>
          <Placeholder as="div" animation="glow"><Placeholder xs={10} /></Placeholder>
        </Card.Body>
      </Card>
    );
  }

  if (run?.verdict == null) {
    return (
      <Card className="selfevolving-section-card">
        <Card.Body>
          <h5 className="mb-1">Verdict Panel</h5>
          <p className="text-body-secondary small mb-0">Select a run to inspect outcome and process verdicts.</p>
        </Card.Body>
      </Card>
    );
  }

  return (
    <Card className="selfevolving-section-card">
      <Card.Body>
        <div className="d-flex flex-wrap align-items-center gap-2 mb-3">
          <h5 className="mb-0">Verdict Panel</h5>
          <Badge bg={toBadgeVariant(run.verdict.outcomeStatus)}>{run.verdict.outcomeStatus ?? 'unknown'}</Badge>
          <Badge bg={toBadgeVariant(run.verdict.processStatus)}>{run.verdict.processStatus ?? 'unknown'}</Badge>
          <span className="small text-body-secondary">Confidence {run.verdict.confidence ?? 0}</span>
        </div>

        <div className="selfevolving-verdict-block mb-3">
          <div className="small text-uppercase text-body-secondary mb-1">Outcome</div>
          <div>{run.verdict.outcomeSummary ?? 'No outcome summary available.'}</div>
        </div>

        <div className="selfevolving-verdict-block mb-3">
          <div className="small text-uppercase text-body-secondary mb-1">Process</div>
          <div>{run.verdict.processSummary ?? 'No process summary available.'}</div>
        </div>

        <div className="small text-uppercase text-body-secondary mb-1">Process Findings</div>
        {run.verdict.processFindings.length === 0 ? (
          <div className="text-body-secondary small">No process findings were recorded for this run.</div>
        ) : (
          <ul className="mb-0 ps-3">
            {run.verdict.processFindings.map((finding) => (
              <li key={finding}>{finding}</li>
            ))}
          </ul>
        )}
      </Card.Body>
    </Card>
  );
}
