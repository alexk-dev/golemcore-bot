import { useReducer } from 'react';
import toast from 'react-hot-toast';
import type { PromptSection } from '../api/prompts';
import {
  arePromptDraftsEqual,
  findPriorityConflictName,
  isPromptNameValid,
  type PromptDraft,
} from '../components/prompts/promptFormUtils';
import {
  useCreatePrompt,
  useDeletePrompt,
  usePreviewPrompt,
  usePrompts,
  useReorderPrompts,
  useUpdatePrompt,
} from './usePrompts';
import {
  performCreatePrompt,
  performPromptReorder,
  performSavePrompt,
  requestPromptPreview,
  runPendingPromptAction,
  showPromptError,
} from './promptsWorkspaceCommands';
import {
  INITIAL_PROMPTS_WORKSPACE_STATE,
  promptsWorkspaceReducer,
} from './promptsWorkspaceState';
import { useCatalogSelectionSync, useDraftPreviewSync } from './promptsWorkspaceEffects';

const EMPTY_PROMPTS: PromptSection[] = [];

export interface UsePromptsWorkspaceResult {
  sections: PromptSection[];
  error: unknown;
  isLoading: boolean;
  isError: boolean;
  selectedName: string | null;
  draft: PromptDraft | null;
  preview: string;
  previewError: string | null;
  isDirty: boolean;
  priorityConflictName: string | null;
  isCreating: boolean;
  isSaving: boolean;
  isPreviewing: boolean;
  isDeleting: boolean;
  isReordering: boolean;
  isDeleteDialogOpen: boolean;
  isUnsavedDialogOpen: boolean;
  createResetToken: number;
  requestCreate: (name: string) => Promise<boolean>;
  requestSelect: (section: PromptSection) => void;
  requestReorder: (sourceName: string, targetName: string) => Promise<void>;
  updateDraft: (draft: PromptDraft) => void;
  resetDraft: () => void;
  saveDraft: () => Promise<boolean>;
  previewDraft: () => Promise<void>;
  requestDelete: () => void;
  confirmDelete: () => Promise<void>;
  saveAndContinue: () => Promise<void>;
  discardAndContinue: () => void;
  closeDeleteDialog: () => void;
  closeUnsavedDialog: () => void;
}

export function usePromptsWorkspace(): UsePromptsWorkspaceResult {
  const { data, isLoading, isError, error } = usePrompts();
  const createMutation = useCreatePrompt();
  const updateMutation = useUpdatePrompt();
  const deleteMutation = useDeletePrompt();
  const previewMutation = usePreviewPrompt();
  const reorderMutation = useReorderPrompts();
  const [state, dispatch] = useReducer(promptsWorkspaceReducer, INITIAL_PROMPTS_WORKSPACE_STATE);

  const sections = data ?? EMPTY_PROMPTS;
  const selectedSection = state.selectedName == null
    ? null
    : sections.find((section) => section.name === state.selectedName) ?? null;
  const isDirty = state.draft != null && selectedSection != null
    ? !arePromptDraftsEqual(state.draft, selectedSection)
    : false;
  const priorityConflictName = state.draft == null ? null : findPriorityConflictName(state.draft, sections);

  useCatalogSelectionSync({
    sections,
    selectedSection,
    selectedName: state.selectedName,
    draft: state.draft,
    dispatch,
  });
  useDraftPreviewSync({
    draft: state.draft,
    previewMutation,
    dispatch,
  });

  const selectPrompt = (section: PromptSection): void => {
    dispatch({ type: 'selectPrompt', section });
  };

  const saveDraft = async (): Promise<boolean> =>
    performSavePrompt({ draft: state.draft, updateMutation, selectPrompt });

  const requestCreate = async (name: string): Promise<boolean> => {
    if (!isPromptNameValid(name)) {
      toast.error('Prompt name must match [a-z0-9][a-z0-9-]*');
      return false;
    }

    if (sections.some((section) => section.name === name)) {
      toast.error('Prompt already exists');
      return false;
    }

    if (isDirty) {
      dispatch({ type: 'openUnsavedDialog', pendingAction: { type: 'create', name } });
      return false;
    }

    return performCreatePrompt({ name, sections, createMutation, selectPrompt, dispatch });
  };

  const requestSelect = (section: PromptSection): void => {
    if (state.selectedName === section.name) {
      return;
    }

    if (isDirty) {
      dispatch({ type: 'openUnsavedDialog', pendingAction: { type: 'select', name: section.name } });
      return;
    }

    selectPrompt(section);
  };

  const requestReorder = async (sourceName: string, targetName: string): Promise<void> => {
    if (sourceName === targetName) {
      return;
    }

    if (isDirty) {
      dispatch({
        type: 'openUnsavedDialog',
        pendingAction: { type: 'reorder', name: sourceName, targetName },
      });
      return;
    }

    await performPromptReorder({
      sourceName,
      targetName,
      sections,
      selectedName: state.selectedName,
      reorderMutation,
      selectPrompt,
    });
  };

  const previewDraft = async (): Promise<void> => {
    if (state.draft == null) {
      return;
    }

    const previewState = await requestPromptPreview({ draft: state.draft, previewMutation });
    dispatch({ type: 'setPreviewState', ...previewState });
  };

  const confirmDelete = async (): Promise<void> => {
    if (state.draft?.deletable !== true) {
      return;
    }

    try {
      await deleteMutation.mutateAsync(state.draft.name);
      dispatch({ type: 'clearEditor' });
      toast.success('Prompt deleted');
    } catch (requestError: unknown) {
      showPromptError('Failed to delete prompt', requestError);
    }
  };

  const saveAndContinue = async (): Promise<void> => {
    const pendingAction = state.pendingAction;
    const saved = await saveDraft();

    if (!saved) {
      return;
    }

    await runPendingPromptAction({
      pendingAction,
      sections,
      requestCreate,
      requestReorder,
      selectPrompt,
    });
  };

  const discardAndContinue = (): void => {
    const pendingAction = state.pendingAction;
    dispatch({ type: 'closeUnsavedDialog' });
    void runPendingPromptAction({
      pendingAction,
      sections,
      requestCreate,
      requestReorder,
      selectPrompt,
    });
  };

  return {
    sections,
    error,
    isLoading,
    isError,
    selectedName: state.selectedName,
    draft: state.draft,
    preview: state.preview,
    previewError: state.previewError,
    isDirty,
    priorityConflictName,
    isCreating: createMutation.isPending,
    isSaving: updateMutation.isPending,
    isPreviewing: previewMutation.isPending,
    isDeleting: deleteMutation.isPending,
    isReordering: reorderMutation.isPending,
    isDeleteDialogOpen: state.isDeleteDialogOpen,
    isUnsavedDialogOpen: state.isUnsavedDialogOpen,
    createResetToken: state.createResetToken,
    requestCreate,
    requestSelect,
    requestReorder,
    updateDraft: (draft: PromptDraft): void => {
      dispatch({ type: 'updateDraft', draft });
    },
    resetDraft: (): void => {
      if (selectedSection != null) {
        selectPrompt(selectedSection);
      }
    },
    saveDraft,
    previewDraft,
    requestDelete: (): void => {
      if (state.draft?.deletable === true) {
        dispatch({ type: 'openDeleteDialog' });
      }
    },
    confirmDelete,
    saveAndContinue,
    discardAndContinue,
    closeDeleteDialog: (): void => {
      dispatch({ type: 'closeDeleteDialog' });
    },
    closeUnsavedDialog: (): void => {
      dispatch({ type: 'closeUnsavedDialog' });
    },
  };
}
