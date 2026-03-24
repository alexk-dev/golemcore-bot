import type { PromptSection } from '../api/prompts';
import { toPromptDraft, type PromptDraft } from '../components/prompts/promptFormUtils';

export interface PendingPromptAction {
  type: 'select' | 'create' | 'reorder';
  name: string;
  targetName?: string;
}

export interface PromptsWorkspaceState {
  selectedName: string | null;
  draft: PromptDraft | null;
  preview: string;
  previewError: string | null;
  pendingAction: PendingPromptAction | null;
  isUnsavedDialogOpen: boolean;
  isDeleteDialogOpen: boolean;
  createResetToken: number;
}

export type PromptsWorkspaceAction =
  | { type: 'selectPrompt'; section: PromptSection }
  | { type: 'updateDraft'; draft: PromptDraft }
  | { type: 'setPreviewState'; preview: string; previewError: string | null }
  | { type: 'openUnsavedDialog'; pendingAction: PendingPromptAction }
  | { type: 'closeUnsavedDialog' }
  | { type: 'openDeleteDialog' }
  | { type: 'closeDeleteDialog' }
  | { type: 'clearEditor' }
  | { type: 'markCreateHandled' };

export const INITIAL_PROMPTS_WORKSPACE_STATE: PromptsWorkspaceState = {
  selectedName: null,
  draft: null,
  preview: '',
  previewError: null,
  pendingAction: null,
  isUnsavedDialogOpen: false,
  isDeleteDialogOpen: false,
  createResetToken: 0,
};

export function promptsWorkspaceReducer(
  state: PromptsWorkspaceState,
  action: PromptsWorkspaceAction
): PromptsWorkspaceState {
  if (action.type === 'selectPrompt') {
    return {
      ...state,
      selectedName: action.section.name,
      draft: toPromptDraft(action.section),
      preview: '',
      previewError: null,
      pendingAction: null,
      isUnsavedDialogOpen: false,
      isDeleteDialogOpen: false,
    };
  }

  if (action.type === 'updateDraft') {
    return {
      ...state,
      draft: action.draft,
      preview: '',
      previewError: null,
    };
  }

  if (action.type === 'setPreviewState') {
    return {
      ...state,
      preview: action.preview,
      previewError: action.previewError,
    };
  }

  if (action.type === 'openUnsavedDialog') {
    return {
      ...state,
      pendingAction: action.pendingAction,
      isUnsavedDialogOpen: true,
    };
  }

  if (action.type === 'closeUnsavedDialog') {
    return {
      ...state,
      pendingAction: null,
      isUnsavedDialogOpen: false,
    };
  }

  if (action.type === 'openDeleteDialog') {
    return {
      ...state,
      isDeleteDialogOpen: true,
    };
  }

  if (action.type === 'closeDeleteDialog') {
    return {
      ...state,
      isDeleteDialogOpen: false,
    };
  }

  if (action.type === 'markCreateHandled') {
    return {
      ...state,
      createResetToken: state.createResetToken + 1,
    };
  }

  if (action.type === 'clearEditor') {
    return INITIAL_PROMPTS_WORKSPACE_STATE;
  }

  return state;
}
