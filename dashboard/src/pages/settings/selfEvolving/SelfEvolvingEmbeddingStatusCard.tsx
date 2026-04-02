import type { ReactElement } from 'react';
import { Badge, Button, Col, Row } from 'react-bootstrap';

import type { SelfEvolvingTacticSearchStatus } from '../../../api/selfEvolving';

interface SelfEvolvingEmbeddingStatusCardProps {
  status: SelfEvolvingTacticSearchStatus | null;
  isInstalling?: boolean;
  onInstall?: () => void;
}

function booleanLabel(value: boolean | null, positive: string, negative: string): string {
  return value === null ? 'Unknown' : value ? positive : negative;
}

function modeLabel(mode: string | null): string {
  if (mode === 'hybrid') {
    return 'Hybrid';
  }
  if (mode === 'bm25') {
    return 'BM25-only';
  }
  return mode ?? 'Unknown';
}

export function SelfEvolvingEmbeddingStatusCard({
  status,
  isInstalling = false,
  onInstall,
}: SelfEvolvingEmbeddingStatusCardProps): ReactElement {
  const showInstallAction = status?.provider === 'ollama'
    && status.model != null
    && status.model.length > 0
    && status.modelAvailable === false
    && onInstall != null;
  return (
    <div className="rounded-3 border border-border/70 bg-card/40 p-3 mb-4">
      <div className="d-flex align-items-center justify-content-between gap-2 mb-2">
        <h6 className="mb-0">Local embedding runtime status</h6>
        <div className="d-flex align-items-center gap-2">
          <Badge bg={status?.degraded ? 'warning' : 'info-subtle'} text={status?.degraded ? 'dark' : 'info'}>
            {modeLabel(status?.mode ?? null)}
          </Badge>
          {showInstallAction && (
            <Button type="button" size="sm" variant="primary" onClick={onInstall} disabled={isInstalling}>
              {isInstalling ? 'Installing...' : 'Install model'}
            </Button>
          )}
        </div>
      </div>
      <p className="text-body-secondary small mb-3">
        {status?.reason ?? 'No local embedding runtime degradation has been reported.'}
      </p>

      <Row className="g-3">
        <Col md={6}>
          <div className="small text-body-secondary">Provider</div>
          <div>{status?.provider ?? 'Not configured'}</div>
        </Col>
        <Col md={6}>
          <div className="small text-body-secondary">Model</div>
          <div>{status?.model ?? 'Not configured'}</div>
        </Col>
        <Col md={6}>
          <div className="small text-body-secondary">Runtime healthy</div>
          <div>{booleanLabel(status?.runtimeHealthy ?? null, 'Healthy', 'Unavailable')}</div>
        </Col>
        <Col md={6}>
          <div className="small text-body-secondary">Model available</div>
          <div>{booleanLabel(status?.modelAvailable ?? null, 'Available', 'Missing')}</div>
        </Col>
        <Col md={6}>
          <div className="small text-body-secondary">Auto-install configured</div>
          <div>{booleanLabel(status?.autoInstallConfigured ?? null, 'Enabled', 'Disabled')}</div>
        </Col>
        <Col md={6}>
          <div className="small text-body-secondary">Pull on start configured</div>
          <div>{booleanLabel(status?.pullOnStartConfigured ?? null, 'Enabled', 'Disabled')}</div>
        </Col>
        <Col md={6}>
          <div className="small text-body-secondary">Pull attempted</div>
          <div>{booleanLabel(status?.pullAttempted ?? null, 'Yes', 'No')}</div>
        </Col>
        <Col md={6}>
          <div className="small text-body-secondary">Pull succeeded</div>
          <div>{booleanLabel(status?.pullSucceeded ?? null, 'Yes', 'No')}</div>
        </Col>
        <Col md={12}>
          <div className="small text-body-secondary">Updated</div>
          <div>{status?.updatedAt ?? 'Unknown'}</div>
        </Col>
      </Row>
    </div>
  );
}
