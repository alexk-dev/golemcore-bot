import type { Dispatch } from 'react';
import toast from 'react-hot-toast';
import type { PromptReorderPayload, PromptSection, PromptSectionPayload } from '../api/prompts';
import {
  buildPromptReorderRequests,
  getNextPromptOrder,
  reorderPromptSections,
  toPromptPayload,
  type PromptDraft,
} from '../components/prompts/promptFormUtils';
import type { PendingPromptAction, PromptsWorkspaceAction } from './promptsWorkspaceState';
import { extractErrorMessage } from '../utils/extractErrorMessage';

export interface PromptSectionMutation {
  mutateAsync: (variables: { name: string; section: PromptSectionPayload }) => Promise<PromptSection>;
}

export interface PromptPreviewMutation {
  mutateAsync: (variables: { name: string; section: PromptSectionPayload }) => Promise<{ rendered: string }>;
}

export interface PromptReorderMutation {
  mutateAsync: (entries: PromptReorderPayload[]) => Promise<void>;
}

export interface PreviewState {
  preview: string;
  previewError: string | null;
}

export interface RequestPromptPreviewArgs {
  draft: PromptDraft;
  previewMutation: PromptPreviewMutation;
}

export async function requestPromptPreview({
  draft,
  previewMutation,
}: RequestPromptPreviewArgs): Promise<PreviewState> {
  try {
    const result = await previewMutation.mutateAsync({
      name: draft.name,
      section: toPromptPayload(draft),
    });

    return {
      preview: result.rendered,
      previewError: null,
    };
  } catch (requestError: unknown) {
    return {
      preview: '',
      previewError: extractErrorMessage(requestError),
    };
  }
}

export interface PerformCreatePromptArgs {
  name: string;
  sections: PromptSection[];
  createMutation: PromptSectionMutation;
  selectPrompt: (section: PromptSection) => void;
  dispatch: Dispatch<PromptsWorkspaceAction>;
}

export async function performCreatePrompt({
  name,
  sections,
  createMutation,
  selectPrompt,
  dispatch,
}: PerformCreatePromptArgs): Promise<boolean> {
  try {
    const created = await createMutation.mutateAsync({
      name,
      section: {
        description: '',
        order: getNextPromptOrder(sections),
        enabled: true,
        content: '',
      },
    });
    selectPrompt(created);
    dispatch({ type: 'markCreateHandled' });
    toast.success('Prompt created');
    return true;
  } catch (requestError: unknown) {
    showPromptError('Failed to create prompt', requestError);
    return false;
  }
}

export interface PerformSavePromptArgs {
  draft: PromptDraft | null;
  updateMutation: PromptSectionMutation;
  selectPrompt: (section: PromptSection) => void;
}

export async function performSavePrompt({
  draft,
  updateMutation,
  selectPrompt,
}: PerformSavePromptArgs): Promise<boolean> {
  if (draft == null) {
    return false;
  }

  try {
    const updated = await updateMutation.mutateAsync({
      name: draft.name,
      section: toPromptPayload(draft),
    });
    selectPrompt(updated);
    toast.success('Prompt saved');
    return true;
  } catch (requestError: unknown) {
    showPromptError('Failed to save prompt', requestError);
    return false;
  }
}

export interface PerformPromptReorderArgs {
  sourceName: string;
  targetName: string;
  sections: PromptSection[];
  selectedName: string | null;
  reorderMutation: PromptReorderMutation;
  selectPrompt: (section: PromptSection) => void;
}

export async function performPromptReorder({
  sourceName,
  targetName,
  sections,
  selectedName,
  reorderMutation,
  selectPrompt,
}: PerformPromptReorderArgs): Promise<void> {
  if (sourceName === targetName) {
    return;
  }

  const reorderedSections = reorderPromptSections(sections, sourceName, targetName);
  const reorderRequests = buildPromptReorderRequests(sections, reorderedSections);

  if (reorderRequests.length === 0) {
    return;
  }

  try {
    await reorderMutation.mutateAsync(reorderRequests);
    const nextSelectedSection = selectedName == null
      ? null
      : reorderedSections.find((section) => section.name === selectedName) ?? null;

    if (nextSelectedSection != null) {
      selectPrompt(nextSelectedSection);
    }

    toast.success('Prompt priorities updated');
  } catch (requestError: unknown) {
    showPromptError('Failed to reorder prompts', requestError);
  }
}

export interface RunPendingPromptActionArgs {
  pendingAction: PendingPromptAction | null;
  sections: PromptSection[];
  requestCreate: (name: string) => Promise<boolean>;
  requestReorder: (sourceName: string, targetName: string) => Promise<void>;
  selectPrompt: (section: PromptSection) => void;
}

export async function runPendingPromptAction({
  pendingAction,
  sections,
  requestCreate,
  requestReorder,
  selectPrompt,
}: RunPendingPromptActionArgs): Promise<void> {
  if (pendingAction == null) {
    return;
  }

  if (pendingAction.type === 'select') {
    const nextSection = sections.find((section) => section.name === pendingAction.name);
    if (nextSection != null) {
      selectPrompt(nextSection);
    }
    return;
  }

  if (pendingAction.type === 'reorder' && pendingAction.targetName != null) {
    await requestReorder(pendingAction.name, pendingAction.targetName);
    return;
  }

  await requestCreate(pendingAction.name);
}

export function showPromptError(prefix: string, requestError: unknown): void {
  toast.error(`${prefix}: ${extractErrorMessage(requestError)}`);
}
