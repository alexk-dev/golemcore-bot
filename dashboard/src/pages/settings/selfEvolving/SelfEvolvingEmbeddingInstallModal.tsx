import type { ReactElement } from 'react';
import { ProgressBar, Spinner } from '../../../components/ui/tailwind-components';

interface SelfEvolvingEmbeddingInstallModalProps {
  show: boolean;
  model: string | null;
}

export function SelfEvolvingEmbeddingInstallModal({
  show,
  model,
}: SelfEvolvingEmbeddingInstallModalProps): ReactElement | null {
  if (!show || model == null || model.length === 0) {
    return null;
  }

  return (
    <div
      className="position-fixed top-0 start-0 z-3 w-100 h-100 d-flex align-items-center justify-content-center bg-dark bg-opacity-50 px-3"
      role="dialog"
      aria-modal="true"
      aria-label="Installing embedding model"
    >
      <div className="col-12 col-sm-10 col-md-7 col-lg-5">
        <div className="bg-body border rounded-4 shadow p-4">
          <div className="d-flex align-items-center gap-2 mb-3">
            <Spinner size="sm" animation="border" />
            <div className="fw-semibold">Installing local embedding model</div>
          </div>
          <div className="text-body-secondary small mb-3">
            Installing <code>{model}</code> through the local Ollama runtime.
          </div>
          <ProgressBar now={68} aria-label="Embedding installation progress" className="mb-3" />
          <div className="text-body-secondary small">
            This dialog closes automatically when the model is ready.
          </div>
        </div>
      </div>
    </div>
  );
}
