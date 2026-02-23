import type { ReactElement } from 'react';
import { Badge, Button } from 'react-bootstrap';
import { FiRefreshCw, FiSave } from 'react-icons/fi';

export interface IdeHeaderProps {
  hasDirtyTabs: boolean;
  dirtyTabsCount: number;
  isRefreshingTree: boolean;
  canSaveActiveTab: boolean;
  isSaving: boolean;
  onRefreshTree: () => void;
  onSaveActiveTab: () => void;
}

export function IdeHeader({
  hasDirtyTabs,
  dirtyTabsCount,
  isRefreshingTree,
  canSaveActiveTab,
  isSaving,
  onRefreshTree,
  onSaveActiveTab,
}: IdeHeaderProps): ReactElement {
  return (
    <div className="section-header d-flex align-items-start justify-content-between gap-3 flex-wrap">
      <div>
        <h4 className="mb-1">IDE</h4>
        <p className="mb-0 text-body-secondary small">
          Browse workspace files, edit code with syntax highlighting, and save back to the bot workspace.
        </p>
      </div>

      <div className="d-flex align-items-center gap-2 flex-wrap">
        <Badge bg={hasDirtyTabs ? 'warning' : 'secondary'} text={hasDirtyTabs ? 'dark' : 'light'}>
          {dirtyTabsCount} unsaved
        </Badge>
        <Button size="sm" variant="secondary" onClick={onRefreshTree} disabled={isRefreshingTree}>
          <FiRefreshCw size={14} className="me-1" />
          {isRefreshingTree ? 'Refreshing...' : 'Refresh tree'}
        </Button>
        <Button size="sm" onClick={onSaveActiveTab} disabled={!canSaveActiveTab}>
          <FiSave size={14} className="me-1" />
          {isSaving ? 'Saving...' : 'Save'}
        </Button>
      </div>
    </div>
  );
}
