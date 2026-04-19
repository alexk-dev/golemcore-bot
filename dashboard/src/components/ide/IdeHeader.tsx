import type { ReactElement } from 'react';
import { FiCommand, FiEdit3, FiFolder, FiMinus, FiPlus, FiSave } from 'react-icons/fi';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';

export interface IdeHeaderProps {
  activeFileLabel: string | null;
  isMobileLayout: boolean;
  hasDirtyTabs: boolean;
  dirtyTabsCount: number;
  canSaveActiveTab: boolean;
  canOpenInlineEdit: boolean;
  isSaving: boolean;
  onSaveActiveTab: () => void;
  onOpenInlineEdit: () => void;
  onOpenQuickOpen: () => void;
  onOpenExplorer: () => void;
  onIncreaseSidebarWidth: () => void;
  onDecreaseSidebarWidth: () => void;
}

export function IdeHeader({
  activeFileLabel,
  isMobileLayout,
  hasDirtyTabs,
  dirtyTabsCount,
  canSaveActiveTab,
  canOpenInlineEdit,
  isSaving,
  onSaveActiveTab,
  onOpenInlineEdit,
  onOpenQuickOpen,
  onOpenExplorer,
  onIncreaseSidebarWidth,
  onDecreaseSidebarWidth,
}: IdeHeaderProps): ReactElement {
  return (
    <div className="section-header flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0 flex-1">
        <h4 className="mb-1">IDE</h4>
        <p className="text-sm text-muted-foreground">
          Open a file, make a quick change, and save it back to the workspace.
        </p>
        {isMobileLayout && (
          <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
            <span className="font-semibold uppercase tracking-[0.16em]">Current file</span>
            <span
              className="max-w-full truncate rounded-full border border-border/80 bg-background/80 px-3 py-1 text-foreground"
              title={activeFileLabel ?? 'No file selected'}
            >
              {activeFileLabel ?? 'No file selected'}
            </span>
          </div>
        )}
      </div>

      <div className="ide-toolbar flex flex-wrap items-center gap-2">
        <Button
          size="sm"
          variant="secondary"
          className="lg:hidden"
          onClick={onOpenExplorer}
          aria-label="Open file explorer"
        >
          <FiFolder size={14} />
          Files
        </Button>

        <Button size="sm" variant="secondary" onClick={onOpenQuickOpen} title="Quick Open (Ctrl/Cmd+P)">
          <FiCommand size={14} />
          Quick Open
        </Button>

        <Button
          size="sm"
          variant="secondary"
          onClick={onOpenInlineEdit}
          disabled={!canOpenInlineEdit}
          title="Inline edit selection (Ctrl/Cmd+K)"
        >
          <FiEdit3 size={14} />
          Inline Edit
        </Button>

        <Button
          size="sm"
          variant="secondary"
          className="hidden lg:inline-flex"
          onClick={onDecreaseSidebarWidth}
          aria-label="Decrease file tree width"
        >
          <FiMinus size={14} />
        </Button>
        <Button
          size="sm"
          variant="secondary"
          className="hidden lg:inline-flex"
          onClick={onIncreaseSidebarWidth}
          aria-label="Increase file tree width"
        >
          <FiPlus size={14} />
        </Button>

        <Badge variant={hasDirtyTabs ? 'warning' : 'secondary'}>
          {dirtyTabsCount} unsaved
        </Badge>

        <Button size="sm" onClick={onSaveActiveTab} disabled={!canSaveActiveTab}>
          <FiSave size={14} />
          {isSaving ? 'Saving...' : 'Save'}
        </Button>
      </div>
    </div>
  );
}
