import type { ReactElement } from 'react';
import { Badge, Button } from 'react-bootstrap';
import type { LlmProviderImportResult } from '../../api/settings';

interface ProviderModelImportResultModalProps {
  show: boolean;
  result: LlmProviderImportResult | null;
  onHide: () => void;
}

interface ResultSectionProps {
  title: string;
  items: string[];
  emptyText: string;
}

function ResultSection({ title, items, emptyText }: ResultSectionProps): ReactElement {
  return (
    <section className="rounded-4 border border-border/70 bg-card/70 p-3">
      <div className="small fw-semibold text-uppercase text-body-secondary mb-2">{title}</div>
      {items.length > 0 ? (
        <ul className="mb-0 ps-3 small">
          {items.map((item) => (
            <li key={item}>
              <code>{item}</code>
            </li>
          ))}
        </ul>
      ) : (
        <div className="small text-body-secondary">{emptyText}</div>
      )}
    </section>
  );
}

export function ProviderModelImportResultModal({
  show,
  result,
  onHide,
}: ProviderModelImportResultModalProps): ReactElement | null {
  if (!show || result == null) {
    return null;
  }

  return (
    <div
      className="position-fixed top-0 start-0 z-3 w-100 h-100 d-flex align-items-center justify-content-center bg-dark bg-opacity-50 px-3 py-4"
      role="dialog"
      aria-modal="true"
      aria-label="Provider import result"
    >
      <div className="col-12 col-xl-8">
        <div className="rounded-4 border border-border/80 bg-card/95 shadow-lg overflow-hidden">
          <div className="border-bottom border-border/70 p-4">
            <div className="d-flex flex-wrap align-items-center gap-2 mb-2">
              <Badge bg={result.providerSaved ? 'success' : 'secondary'}>Provider Saved</Badge>
              <Badge bg="info">{result.providerName}</Badge>
            </div>
            <h2 className="h5 mb-2">Provider saved with safe model import</h2>
            <div className="small text-body-secondary">
              Best-effort import finished without overwriting existing catalog entries.
            </div>
            <div className="small mt-3">
              <span className="fw-semibold">Resolved endpoint:</span>{' '}
              {result.resolvedEndpoint != null ? <code>{result.resolvedEndpoint}</code> : <span className="text-body-secondary">Unavailable</span>}
            </div>
          </div>

          <div className="p-4 d-grid gap-3">
            <ResultSection
              title="Added Models"
              items={result.addedModels}
              emptyText="No new models were added."
            />
            <ResultSection
              title="Skipped Existing Models"
              items={result.skippedModels}
              emptyText="No existing models were skipped."
            />
            <ResultSection
              title="Errors"
              items={result.errors}
              emptyText="No import errors were reported."
            />
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
