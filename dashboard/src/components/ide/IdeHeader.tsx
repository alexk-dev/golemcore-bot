import type { ReactElement } from 'react';
import { FiCommand, FiMinus, FiPlus, FiRefreshCw, FiSave, FiSearch } from 'react-icons/fi';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Input } from '../ui/field';

export interface IdeHeaderProps {
  hasDirtyTabs: boolean;
  dirtyTabsCount: number;
  isRefreshingTree: boolean;
  canSaveActiveTab: boolean;
  isSaving: boolean;
  treeSearchQuery: string;
  onTreeSearchQueryChange: (value: string) => void;
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
  treeSearchQuery,
  onTreeSearchQueryChange,
  onRefreshTree,
  onSaveActiveTab,
  onOpenQuickOpen,
  onIncreaseSidebarWidth,
  onDecreaseSidebarWidth,
}: IdeHeaderProps): ReactElement {
  return (
    <div className="section-header flex flex-wrap items-start justify-between gap-3">
      <div>
        <h4 className="mb-1">IDE</h4>
        <p className="text-sm text-muted-foreground">
          Browse workspace files, edit code with syntax highlighting, and save back to the bot workspace.
        </p>
      </div>

      <div className="ide-toolbar flex flex-wrap items-center gap-2">
        <label className="ide-search-group relative block">
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground">
            <FiSearch size={14} />
          </span>
          <Input
            className="h-9 rounded-xl border-border/80 bg-background/80 pl-11 pr-3 text-sm shadow-none"
            value={treeSearchQuery}
            onChange={(event) => onTreeSearchQueryChange(event.target.value)}
            placeholder="Search in file tree"
            aria-label="Search in file tree"
          />
        </label>

        <Button size="sm" variant="secondary" onClick={onOpenQuickOpen} title="Quick Open (Ctrl/Cmd+P)">
          <FiCommand size={14} />
          Quick Open
        </Button>

        <Button size="sm" variant="secondary" onClick={onDecreaseSidebarWidth} aria-label="Decrease file tree width">
          <FiMinus size={14} />
        </Button>
        <Button size="sm" variant="secondary" onClick={onIncreaseSidebarWidth} aria-label="Increase file tree width">
          <FiPlus size={14} />
        </Button>

        <Badge variant={hasDirtyTabs ? 'warning' : 'secondary'}>
          {dirtyTabsCount} unsaved
        </Badge>

        <Button size="sm" variant="secondary" onClick={onRefreshTree} disabled={isRefreshingTree}>
          <FiRefreshCw size={14} className={isRefreshingTree ? 'animate-spin' : undefined} />
          {isRefreshingTree ? 'Refreshing...' : 'Refresh tree'}
        </Button>

        <Button size="sm" onClick={onSaveActiveTab} disabled={!canSaveActiveTab}>
          <FiSave size={14} />
          {isSaving ? 'Saving...' : 'Save'}
        </Button>
      </div>
    </div>
  );
}
