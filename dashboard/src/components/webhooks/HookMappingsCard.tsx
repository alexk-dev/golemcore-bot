import type { ReactElement } from 'react';
import { Button, Card } from 'react-bootstrap';
import type { HookMapping } from '../../api/webhooks';
import { HookMappingForm } from './HookMappingForm';
import { HookMappingsTable } from './HookMappingsTable';

interface HookMappingsCardProps {
  mappings: HookMapping[];
  activeEditIndex: number | null;
  onToggleEdit: (index: number) => void;
  onDelete: (index: number) => void;
  onAdd: () => void;
  onUpdate: (index: number, mapping: HookMapping) => void;
  onCopyEndpoint: (name: string) => void;
}

export function HookMappingsCard({
  mappings,
  activeEditIndex,
  onToggleEdit,
  onDelete,
  onAdd,
  onUpdate,
  onCopyEndpoint,
}: HookMappingsCardProps): ReactElement {
  return (
    <Card className="settings-card mb-3">
      <Card.Body>
        <div className="d-flex align-items-center justify-content-between mb-3">
          <div>
            <h2 className="h6 mb-1">Hook mappings</h2>
            <p className="small text-body-secondary mb-0">
              Create named endpoints under <code>/api/hooks/{'{name}'}</code>.
            </p>
          </div>
          <Button type="button" size="sm" variant="primary" onClick={onAdd}>
            Add Hook
          </Button>
        </div>

        <HookMappingsTable
          mappings={mappings}
          activeEditIndex={activeEditIndex}
          onToggleEdit={onToggleEdit}
          onDelete={onDelete}
        />

        {activeEditIndex != null && mappings[activeEditIndex] != null && (
          <div className="mt-3">
            <HookMappingForm
              mapping={mappings[activeEditIndex]}
              onChange={(nextMapping) => onUpdate(activeEditIndex, nextMapping)}
              onCopyEndpoint={onCopyEndpoint}
            />
          </div>
        )}
      </Card.Body>
    </Card>
  );
}
