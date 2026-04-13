import type { ReactElement } from 'react';
import { Badge, Button } from '../../components/ui/tailwind-components';
import type { LlmProviderTestResult } from '../../api/settings';
import { Modal } from '../../components/ui/overlay';

interface ProviderModelTestResultModalProps {
  show: boolean;
  result: LlmProviderTestResult | null;
  onHide: () => void;
}

export function ProviderModelTestResultModal({
  show,
  result,
  onHide,
}: ProviderModelTestResultModalProps): ReactElement | null {
  if (!show || result == null) {
    return null;
  }

  return (
    <Modal show={show} onHide={onHide} centered size="lg" className="overflow-hidden">
      <Modal.Header closeButton>
        <div>
          <div className="d-flex flex-wrap align-items-center gap-2 mb-2">
            <Badge bg={result.success ? 'success' : 'danger'}>{result.success ? 'Reachable' : 'Failed'}</Badge>
            <Badge bg="secondary">{result.providerName}</Badge>
          </div>
          <Modal.Title>Provider Test Result</Modal.Title>
          <dl className="row mb-0 mt-3 small">
            <dt className="col-sm-3">Mode</dt>
            <dd className="col-sm-9 mb-2">
              <code>{result.mode}</code>
            </dd>
            <dt className="col-sm-3">Resolved endpoint</dt>
            <dd className="col-sm-9 mb-0">
              {result.resolvedEndpoint != null ? <code>{result.resolvedEndpoint}</code> : <span className="text-body-secondary">Unavailable</span>}
            </dd>
          </dl>
        </div>
      </Modal.Header>

      <Modal.Body className="space-y-3">
        {result.error != null && (
          <section className="rounded-4 border border-danger-subtle bg-danger-subtle p-3">
            <div className="small fw-semibold text-uppercase text-danger mb-2">Error</div>
            <div className="small">{result.error}</div>
          </section>
        )}

        <section className="rounded-4 border border-border/70 bg-card/70 p-3">
          <div className="small fw-semibold text-uppercase text-body-secondary mb-2">Models</div>
          {result.models.length > 0 ? (
            <ul className="mb-0 ps-3 small">
              {result.models.map((model) => (
                <li key={model}>
                  <code>{model}</code>
                </li>
              ))}
            </ul>
          ) : (
            <div className="small text-body-secondary">The provider returned no models.</div>
          )}
        </section>
      </Modal.Body>

      <Modal.Footer>
        <Button type="button" variant="primary" size="sm" onClick={onHide}>
          Close
        </Button>
      </Modal.Footer>
    </Modal>
  );
}
