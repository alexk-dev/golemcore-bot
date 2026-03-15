import { type Dispatch, useEffect } from 'react';
import type { PromptSection } from '../api/prompts';
import type { PromptDraft } from '../components/prompts/promptFormUtils';
import type { PromptsWorkspaceAction } from './promptsWorkspaceState';
import { requestPromptPreview, type PromptPreviewMutation } from './promptsWorkspaceCommands';

export interface CatalogSelectionSyncArgs {
  sections: PromptSection[];
  selectedSection: PromptSection | null;
  selectedName: string | null;
  draft: PromptDraft | null;
  dispatch: Dispatch<PromptsWorkspaceAction>;
}

export function useCatalogSelectionSync({
  sections,
  selectedSection,
  selectedName,
  draft,
  dispatch,
}: CatalogSelectionSyncArgs): void {
  useEffect(() => {
    // Keep the editor attached to a real prompt whenever the catalog loads or changes.
    if (sections.length === 0) {
      if (selectedName != null || draft != null) {
        dispatch({ type: 'clearEditor' });
      }
      return;
    }

    if (selectedSection != null && draft != null) {
      return;
    }

    dispatch({ type: 'selectPrompt', section: selectedSection ?? sections[0] });
  }, [dispatch, draft, sections, selectedName, selectedSection]);
}

export interface DraftPreviewSyncArgs {
  draft: PromptDraft | null;
  previewMutation: PromptPreviewMutation;
  dispatch: Dispatch<PromptsWorkspaceAction>;
}

export function useDraftPreviewSync({
  draft,
  previewMutation,
  dispatch,
}: DraftPreviewSyncArgs): void {
  useEffect(() => {
    // Render the preview from the current draft after a short debounce to keep typing responsive.
    if (draft == null) {
      return;
    }

    let cancelled = false;
    const timeoutId = window.setTimeout(() => {
      void requestPromptPreview({ draft, previewMutation }).then((previewState) => {
        if (!cancelled) {
          dispatch({ type: 'setPreviewState', ...previewState });
        }
      });
    }, 300);

    return () => {
      cancelled = true;
      window.clearTimeout(timeoutId);
    };
  }, [dispatch, draft, previewMutation]);
}
