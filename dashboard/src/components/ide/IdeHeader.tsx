import type { ReactElement } from 'react';
import { Badge, Button, Form, InputGroup } from 'react-bootstrap';
import { FiCommand, FiMinus, FiPlus, FiRefreshCw, FiSave, FiSearch } from 'react-icons/fi';

export interface IdeHeaderProps {
  hasDirtyTabs: boolean;
  dirtyTabsCount: number;
  isRefreshingTree: boolean;
  canSaveActiveTab: boolean;
  isSaving: boolean;
  searchQuery: string;
  onSearchQueryChange: (value: string) => void;
  onRefreshTree: () => void;
  onSaveActiveTab: () => void;
  onOpenQuickOpen: () => void;
  onIncreaseSidebarWidth: () => void;
  onDecreaseSidebarWidth: () => void;
}

export function IdeHeader({
  hasDirtyTabs,
  dirtyTabsCount,
  isRefreshingTree,
  canSaveActiveTab,
  isSaving,
  searchQuery,
  onSearchQueryChange,
  onRefreshTree,
  onSaveActiveTab,
  onOpenQuickOpen,
  onIncreaseSidebarWidth,
  onDecreaseSidebarWidth,
}: IdeHeaderProps): ReactElement {
  return (
    <div className="section-header d-flex align-items-start justify-content-between gap-3 flex-wrap">
      <div>
        <h4 className="mb-1">IDE</h4>
        <p className="mb-0 text-body-secondary small">
          Browse workspace files, edit code with syntax highlighting, and save back to the bot workspace.
        </p>
      </div>

      <div className="d-flex align-items-center gap-2 flex-wrap ide-toolbar">
        <InputGroup size="sm" className="ide-search-group">
          <InputGroup.Text>
            <FiSearch size={14} />
          </InputGroup.Text>
          <Form.Control
            value={searchQuery}
            onChange={(event) => onSearchQueryChange(event.target.value)}
            placeholder="Search files"
            aria-label="Search files"
          />
        </InputGroup>

        <Button size="sm" variant="secondary" onClick={onOpenQuickOpen} title="Quick Open (Ctrl/Cmd+P)">
          <FiCommand size={14} className="me-1" />
          Quick Open
        </Button>

        <Button size="sm" variant="secondary" onClick={onDecreaseSidebarWidth} aria-label="Decrease file tree width">
          <FiMinus size={14} />
        </Button>
        <Button size="sm" variant="secondary" onClick={onIncreaseSidebarWidth} aria-label="Increase file tree width">
          <FiPlus size={14} />
        </Button>

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
