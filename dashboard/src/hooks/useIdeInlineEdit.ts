import { useCallback, useEffect, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import { createInlineEdit } from '../api/files';
import { useProposedEditStore } from '../store/proposedEditStore';
import type { IdeTabState } from '../store/ideStore';

export interface IdeInlineEditSelection {
  from: number;
  to: number;
  selectedText: string;
}

export interface UseIdeInlineEditResult {
  currentSelection: IdeInlineEditSelection | null;
  isInlineEditVisible: boolean;
  isSubmittingInlineEdit: boolean;
  handleEditorSelectionChange: (selection: IdeInlineEditSelection | null) => void;
  openInlineEdit: () => void;
  closeInlineEdit: () => void;
  submitInlineEdit: (instruction: string) => void;
}

export interface UseIdeInlineEditOptions {
  activeTab: IdeTabState | null;
}

function normalizeSelection(selection: IdeInlineEditSelection | null): IdeInlineEditSelection | null {
  if (selection == null) {
    return null;
  }
  if (selection.selectedText.trim().length === 0) {
    return null;
  }
  if (selection.from === selection.to) {
    return null;
  }
  return selection;
}

/**
 * Coordinates the v1 inline-edit flow for a selected code range.
 */
export function useIdeInlineEdit({ activeTab }: UseIdeInlineEditOptions): UseIdeInlineEditResult {
  const [currentSelection, setCurrentSelection] = useState<IdeInlineEditSelection | null>(null);
  const [isInlineEditVisible, setInlineEditVisible] = useState<boolean>(false);
  const [isSubmittingInlineEdit, setSubmittingInlineEdit] = useState<boolean>(false);

  const normalizedSelection = useMemo(
    () => normalizeSelection(currentSelection),
    [currentSelection],
  );

  useEffect(() => {
    // Selection belongs to the active file; clear it when the user switches tabs.
    setCurrentSelection(null);
    setInlineEditVisible(false);
  }, [activeTab?.path]);

  const handleEditorSelectionChange = useCallback((selection: IdeInlineEditSelection | null): void => {
    setCurrentSelection(normalizeSelection(selection));
  }, []);

  const openInlineEdit = useCallback((): void => {
    if (activeTab?.editable !== true) {
      return;
    }
    if (normalizedSelection == null) {
      toast.error('Select code before using inline edit');
      return;
    }
    setInlineEditVisible(true);
  }, [activeTab, normalizedSelection]);

  const closeInlineEdit = useCallback((): void => {
    if (isSubmittingInlineEdit) {
      return;
    }
    setInlineEditVisible(false);
  }, [isSubmittingInlineEdit]);

  const submitInlineEdit = useCallback((instruction: string): void => {
    if (activeTab == null || normalizedSelection == null) {
      return;
    }
    const trimmedInstruction = instruction.trim();
    if (trimmedInstruction.length === 0) {
      return;
    }

    setSubmittingInlineEdit(true);
    void createInlineEdit({
      path: activeTab.path,
      content: activeTab.content,
      selectionFrom: normalizedSelection.from,
      selectionTo: normalizedSelection.to,
      selectedText: normalizedSelection.selectedText,
      instruction: trimmedInstruction,
    })
      .then((response) => {
        useProposedEditStore.getState().submitProposal({
          path: response.path,
          sourceContent: activeTab.content,
          before: normalizedSelection.selectedText,
          after: response.replacement,
          instruction: trimmedInstruction,
          selection: normalizedSelection,
        });
        setInlineEditVisible(false);
      })
      .catch((error: unknown) => {
        console.error('Inline edit failed', error);
        toast.error('Inline edit failed');
      })
      .finally(() => {
        setSubmittingInlineEdit(false);
      });
  }, [activeTab, normalizedSelection]);

  return {
    currentSelection: normalizedSelection,
    isInlineEditVisible,
    isSubmittingInlineEdit,
    handleEditorSelectionChange,
    openInlineEdit,
    closeInlineEdit,
    submitInlineEdit,
  };
}
