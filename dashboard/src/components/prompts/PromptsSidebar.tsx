import { type DragEvent, type KeyboardEvent, type ReactElement, useEffect, useState } from 'react';
import type { PromptSection } from '../../api/prompts';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Input } from '../ui/field';
import { isPromptNameValid, normalizePromptName } from './promptFormUtils';
import { PromptCatalogList, PromptCreateCard } from './PromptsSidebarParts';

export interface PromptsSidebarProps {
  sections: PromptSection[];
  selectedName: string | null;
  isCreating: boolean;
  isReordering: boolean;
  hasUnsavedChanges: boolean;
  createResetToken: number;
  onCreate: (name: string) => Promise<boolean>;
  onReorder: (sourceName: string, targetName: string) => Promise<void>;
  onSelect: (section: PromptSection) => void;
}

function getReorderHint(
  normalizedSearch: string,
  hasUnsavedChanges: boolean,
  isCreating: boolean,
  isReordering: boolean
): string {
  if (normalizedSearch.length > 0) {
    return 'Clear search to reorder prompts.';
  }

  if (hasUnsavedChanges) {
    return 'Save or discard current edits before reordering.';
  }

  if (isCreating) {
    return 'Finish creating the new prompt before changing priority order.';
  }

  if (isReordering) {
    return 'Updating prompt priorities...';
  }

  return 'Drag cards or use the arrow controls to rebalance prompt priority.';
}

export function PromptsSidebar({
  sections,
  selectedName,
  isCreating,
  isReordering,
  hasUnsavedChanges,
  createResetToken,
  onCreate,
  onReorder,
  onSelect,
}: PromptsSidebarProps): ReactElement {
  const [searchValue, setSearchValue] = useState('');
  const [newPromptName, setNewPromptName] = useState('');
  const [draggingName, setDraggingName] = useState<string | null>(null);
  const [dropTargetName, setDropTargetName] = useState<string | null>(null);

  const normalizedSearch = searchValue.trim().toLowerCase();
  const normalizedNewPromptName = normalizePromptName(newPromptName);
  const hasExistingPrompt = sections.some((section) => section.name === normalizedNewPromptName);
  const isValidPromptName = normalizedNewPromptName.length === 0 || isPromptNameValid(normalizedNewPromptName);
  const filteredSections = sections.filter((section) => {
    if (normalizedSearch.length === 0) {
      return true;
    }

    return section.name.includes(normalizedSearch) || section.description.toLowerCase().includes(normalizedSearch);
  });
  const canReorder = normalizedSearch.length === 0 && !hasUnsavedChanges && !isCreating && !isReordering;
  const reorderHint = getReorderHint(normalizedSearch, hasUnsavedChanges, isCreating, isReordering);

  const clearDragState = (): void => {
    setDraggingName(null);
    setDropTargetName(null);
  };

  const handleCreate = async (): Promise<void> => {
    if (normalizedNewPromptName.length === 0) {
      return;
    }

    const created = await onCreate(normalizedNewPromptName);
    if (created) {
      setNewPromptName('');
    }
  };

  const handleCreateKeyDown = (event: KeyboardEvent<HTMLInputElement>): void => {
    if (event.key === 'Enter') {
      event.preventDefault();
      void handleCreate();
    }
  };

  const handleMove = (sourceName: string, targetName: string): void => {
    void onReorder(sourceName, targetName);
  };

  const handleDragStart = (event: DragEvent<HTMLDivElement>, sourceName: string): void => {
    if (!canReorder) {
      event.preventDefault();
      return;
    }

    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', sourceName);
    setDraggingName(sourceName);
  };

  const handleDragOver = (event: DragEvent<HTMLDivElement>, targetName: string): void => {
    if (!canReorder || draggingName == null || draggingName === targetName) {
      return;
    }

    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
    setDropTargetName(targetName);
  };

  const handleDragLeave = (targetName: string): void => {
    if (dropTargetName === targetName) {
      setDropTargetName(null);
    }
  };

  const handleDrop = (event: DragEvent<HTMLDivElement>, targetName: string): void => {
    event.preventDefault();
    const sourceName = draggingName ?? event.dataTransfer.getData('text/plain');
    clearDragState();
    if (sourceName.length > 0 && sourceName !== targetName) {
      handleMove(sourceName, targetName);
    }
  };

  useEffect(() => {
    // Clear the create field after a prompt is successfully created elsewhere in the flow.
    setNewPromptName('');
  }, [createResetToken]);

  useEffect(() => {
    // Reset transient drag state when reordering becomes unavailable or completes.
    if (!canReorder) {
      clearDragState();
    }
  }, [canReorder]);

  return (
    <div className="space-y-4">
      <Card className="overflow-hidden">
        <CardHeader>
          <div>
            <CardTitle>Catalog</CardTitle>
            <CardDescription>Manage system prompt sections and their priority order.</CardDescription>
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <label className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
              Search
            </label>
            <Input
              value={searchValue}
              onChange={(event) => setSearchValue(event.target.value)}
              placeholder="identity, rules, custom..."
            />
          </div>

          <PromptCreateCard
            newPromptName={newPromptName}
            isCreating={isCreating}
            hasExistingPrompt={hasExistingPrompt}
            isValidPromptName={isValidPromptName}
            onNameChange={setNewPromptName}
            onCreate={() => {
              void handleCreate();
            }}
            onKeyDown={handleCreateKeyDown}
          />

          <p className="text-sm leading-6 text-muted-foreground">{reorderHint}</p>
        </CardContent>
      </Card>

      <PromptCatalogList
        sections={filteredSections}
        selectedName={selectedName}
        draggingName={draggingName}
        dropTargetName={dropTargetName}
        canReorder={canReorder}
        onSelect={onSelect}
        onMove={handleMove}
        onDragStart={handleDragStart}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onDragEnd={clearDragState}
      />
    </div>
  );
}
