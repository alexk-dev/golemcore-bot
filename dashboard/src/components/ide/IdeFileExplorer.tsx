import type { ReactElement } from 'react';
import { FiPlus, FiRefreshCw, FiSearch } from 'react-icons/fi';
import type { FileTreeNode } from '../../api/files';
import { Button } from '../ui/button';
import { Input } from '../ui/field';
import { FileTreePanel } from './FileTreePanel';

export interface IdeFileExplorerProps {
  nodes: FileTreeNode[] | undefined;
  isLoading: boolean;
  isError: boolean;
  isRefreshing: boolean;
  selectedPath: string | null;
  dirtyPaths: Set<string>;
  searchInputValue: string;
  searchQuery: string;
  onSearchQueryChange: (value: string) => void;
  onRefresh: () => void;
  onCreateAtRoot: () => void;
  onOpenFile: (path: string) => void;
  onRequestCreate: (targetPath: string) => void;
  onRequestRename: (targetPath: string) => void;
  onRequestDelete: (targetPath: string) => void;
}

export function IdeFileExplorer({
  nodes,
  isLoading,
  isError,
  isRefreshing,
  selectedPath,
  dirtyPaths,
  searchInputValue,
  searchQuery,
  onSearchQueryChange,
  onRefresh,
  onCreateAtRoot,
  onOpenFile,
  onRequestCreate,
  onRequestRename,
  onRequestDelete,
}: IdeFileExplorerProps): ReactElement {
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="border-b border-border/80 px-3 py-3">
        <div className="flex items-center gap-2">
          <label className="relative min-w-0 flex-1">
            <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground">
              <FiSearch size={14} />
            </span>
            <Input
              className="h-9 rounded-xl border-border/80 bg-background/80 pl-11 pr-3 text-sm shadow-none"
              value={searchInputValue}
              onChange={(event) => onSearchQueryChange(event.target.value)}
              placeholder="Search files"
              aria-label="Search files"
            />
          </label>

          <Button
            size="sm"
            variant="secondary"
            onClick={onRefresh}
            aria-label="Refresh file tree"
            title="Refresh file tree"
          >
            <FiRefreshCw size={14} className={isRefreshing ? 'animate-spin' : undefined} />
          </Button>

          <Button
            size="sm"
            variant="secondary"
            onClick={onCreateAtRoot}
            aria-label="Create new file"
            title="Create new file"
          >
            <FiPlus size={14} />
          </Button>
        </div>
      </div>

      <div className="min-h-0 flex-1 p-2">
        {isLoading ? (
          <div className="flex h-full items-center justify-center">
            <span
              className="h-5 w-5 animate-spin rounded-full border-2 border-border border-t-primary"
              role="status"
              aria-label="Loading file tree"
            />
          </div>
        ) : isError ? (
          <div
            className="flex items-center justify-between gap-3 rounded-2xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive"
            role="alert"
          >
            <span>Failed to load file tree.</span>
            <Button size="sm" variant="secondary" onClick={onRefresh}>
              Retry
            </Button>
          </div>
        ) : (
          <FileTreePanel
            nodes={nodes ?? []}
            selectedPath={selectedPath}
            dirtyPaths={dirtyPaths}
            searchQuery={searchQuery}
            onOpenFile={onOpenFile}
            onRequestCreate={onRequestCreate}
            onRequestRename={onRequestRename}
            onRequestDelete={onRequestDelete}
          />
        )}
      </div>
    </div>
  );
}
