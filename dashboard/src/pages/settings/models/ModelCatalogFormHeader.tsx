import type { ReactElement } from 'react';
import { FiPlay, FiSave, FiTrash2 } from 'react-icons/fi';

import { Button } from '../../../components/ui/button';
import { CardHeader, CardTitle } from '../../../components/ui/card';
import { Badge } from '../../../components/ui/badge';

interface ModelCatalogFormHeaderProps {
  title: string;
  isExisting: boolean;
  isSaving: boolean;
  isDeleting: boolean;
  isTesting: boolean;
  canTestModel: boolean;
  supportsVision: boolean;
  onSave: () => void;
  onTest: () => void;
  onDeleteClick: () => void;
}

export function ModelCatalogFormHeader({
  title,
  isExisting,
  isSaving,
  isDeleting,
  isTesting,
  canTestModel,
  supportsVision,
  onSave,
  onTest,
  onDeleteClick,
}: ModelCatalogFormHeaderProps): ReactElement {
  return (
    <CardHeader className="items-start">
      <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle>{title}</CardTitle>
          <Badge variant={isExisting ? 'secondary' : 'default'}>{isExisting ? 'Existing' : 'New'}</Badge>
          <Badge variant={supportsVision ? 'info' : 'secondary'}>{supportsVision ? 'Vision enabled' : 'Text only'}</Badge>
        </div>
        <p className="text-sm text-muted-foreground">
          Model IDs act as stable catalog keys. Existing IDs stay locked to keep updates predictable.
        </p>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button onClick={onSave} disabled={isSaving}>
          <FiSave size={15} />
          {isSaving ? 'Saving...' : isExisting ? 'Save Changes' : 'Create Model'}
        </Button>
        <Button variant="secondary" onClick={onTest} disabled={isTesting || !canTestModel}>
          <FiPlay size={15} />
          {isTesting ? 'Testing...' : 'Test Model'}
        </Button>
        {isExisting && (
          <Button variant="secondary" onClick={onDeleteClick} disabled={isDeleting}>
            <FiTrash2 size={15} />
            {isDeleting ? 'Deleting...' : 'Delete'}
          </Button>
        )}
      </div>
    </CardHeader>
  );
}
