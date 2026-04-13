import type { ReactElement } from 'react';
import { Badge, Button } from '../../components/ui/tailwind-components';
import type { LlmProviderImportResult } from '../../api/settings';
import { Modal } from '../../components/ui/overlay';

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
    <Modal show={show} onHide={onHide} centered size="lg" className="overflow-hidden">
      <Modal.Header closeButton>
        <div>
          <div className="d-flex flex-wrap align-items-center gap-2 mb-2">
            <Badge bg={result.providerSaved ? 'success' : 'secondary'}>Provider Saved</Badge>
            <Badge bg="info">{result.providerName}</Badge>
          </div>
          <Modal.Title>Provider saved with safe model import</Modal.Title>
          <div className="small text-body-secondary mt-2">
            Best-effort import finished without overwriting existing catalog entries.
          </div>
          <div className="small mt-3">
            <span className="fw-semibold">Resolved endpoint:</span>{' '}
            {result.resolvedEndpoint != null ? <code>{result.resolvedEndpoint}</code> : <span className="text-body-secondary">Unavailable</span>}
          </div>
        </div>
      </Modal.Header>

      <Modal.Body className="space-y-3">
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
      </Modal.Body>

      <Modal.Footer>
        <Button type="button" variant="primary" size="sm" onClick={onHide}>
          Close
        </Button>
      </Modal.Footer>
    </Modal>
  );
}
