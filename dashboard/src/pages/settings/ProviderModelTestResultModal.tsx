import type { ReactElement } from 'react';
import { Badge, Button } from 'react-bootstrap';
import type { LlmProviderTestResult } from '../../api/settings';

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
    <div
      className="position-fixed top-0 start-0 z-3 w-100 h-100 d-flex align-items-center justify-content-center bg-dark bg-opacity-50 px-3 py-4"
      role="dialog"
      aria-modal="true"
      aria-label="Provider test result"
    >
      <div className="col-12 col-lg-8 col-xl-7">
        <div className="rounded-4 border border-border/80 bg-card/95 shadow-lg overflow-hidden">
          <div className="border-bottom border-border/70 p-4">
            <div className="d-flex flex-wrap align-items-center gap-2 mb-2">
              <Badge bg={result.success ? 'success' : 'danger'}>{result.success ? 'Reachable' : 'Failed'}</Badge>
              <Badge bg="secondary">{result.providerName}</Badge>
            </div>
            <h2 className="h5 mb-3">Provider Test Result</h2>
            <dl className="row mb-0 small">
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

          <div className="p-4 d-grid gap-3">
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
          </div>

          <div className="border-top border-border/70 p-3 d-flex justify-content-end">
            <Button type="button" variant="primary" size="sm" onClick={onHide}>
              Close
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
