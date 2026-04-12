import type { ReactElement } from 'react';
import { Button, Form } from 'react-bootstrap';

export interface ProviderModelSelectionFormProps {
  models: string[];
  selectedModels: string[];
  onToggleModel: (modelId: string) => void;
  onSelectAll: () => void;
  onClearAll: () => void;
  onInvert: () => void;
}

export function ProviderModelSelectionForm({
  models,
  selectedModels,
  onToggleModel,
  onSelectAll,
  onClearAll,
  onInvert,
}: ProviderModelSelectionFormProps): ReactElement | null {
  if (models.length === 0) {
    return null;
  }

  const selectedModelSet = new Set(selectedModels);

  return (
    <section className="mt-3 rounded border p-3">
      <div className="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-2">
        <div>
          <div className="small fw-semibold">Models to import</div>
          <div className="small text-body-secondary">
            {selectedModels.length} of {models.length} selected
          </div>
        </div>
        <div className="d-flex flex-wrap gap-2">
          <Button type="button" variant="secondary" size="sm" onClick={onSelectAll}>
            Select all
          </Button>
          <Button type="button" variant="secondary" size="sm" onClick={onClearAll}>
            Clear all
          </Button>
          <Button type="button" variant="secondary" size="sm" onClick={onInvert}>
            Invert
          </Button>
        </div>
      </div>

      <div className="provider-model-selection-list overflow-auto pe-2">
        {models.map((model) => (
          <Form.Check
            key={model}
            id={`provider-model-${model.replace(/[^a-zA-Z0-9_-]/g, '-')}`}
            type="checkbox"
            className="small mb-1"
            checked={selectedModelSet.has(model)}
            label={<code>{model}</code>}
            onChange={() => onToggleModel(model)}
          />
        ))}
      </div>
    </section>
  );
}
