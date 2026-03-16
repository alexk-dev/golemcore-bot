import { type Dispatch, useEffect } from 'react';
import type { PromptSection } from '../api/prompts';
import type { PromptDraft } from '../components/prompts/promptFormUtils';
import type { PromptsWorkspaceAction } from './promptsWorkspaceState';

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
