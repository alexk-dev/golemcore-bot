import { useCallback, useMemo, useState } from 'react';
import type { IdeTabState } from '../store/ideStore';

function findTabByPath(tabs: IdeTabState[], path: string | null): IdeTabState | null {
  if (path == null) {
    return null;
  }

  return tabs.find((tab) => tab.path === path) ?? null;
}

export interface UseIdeCloseWorkflowOptions {
  openedTabs: IdeTabState[];
  activePath: string | null;
  closeTab: (path: string) => void;
  saveTab: (tab: IdeTabState) => Promise<boolean>;
}

export interface UseIdeCloseWorkflowResult {
  closeCandidate: IdeTabState | null;
  isCloseWithSavePending: boolean;
  requestCloseTab: (path: string) => void;
  requestCloseActiveTab: () => void;
  cancelCloseCandidate: () => void;
  closeCandidateWithoutSaving: () => void;
  saveAndCloseCandidate: () => void;
}

export function useIdeCloseWorkflow({
  openedTabs,
  activePath,
  closeTab,
  saveTab,
}: UseIdeCloseWorkflowOptions): UseIdeCloseWorkflowResult {
  const [closeCandidatePath, setCloseCandidatePath] = useState<string | null>(null);
  const [isCloseWithSavePending, setIsCloseWithSavePending] = useState<boolean>(false);

  const closeCandidate = useMemo(() => {
    return findTabByPath(openedTabs, closeCandidatePath);
  }, [closeCandidatePath, openedTabs]);

  const requestCloseTab = useCallback((path: string): void => {
    const tab = findTabByPath(openedTabs, path);
    if (tab == null) {
      return;
    }

    if (tab.isDirty) {
      setCloseCandidatePath(path);
      return;
    }

    closeTab(path);
  }, [closeTab, openedTabs]);

  const requestCloseActiveTab = useCallback((): void => {
    if (activePath == null) {
      return;
    }

    requestCloseTab(activePath);
  }, [activePath, requestCloseTab]);

  const cancelCloseCandidate = useCallback((): void => {
    setCloseCandidatePath(null);
  }, []);

  const closeCandidateWithoutSaving = useCallback((): void => {
    if (closeCandidatePath != null) {
      closeTab(closeCandidatePath);
    }
    setCloseCandidatePath(null);
  }, [closeCandidatePath, closeTab]);

  const saveAndCloseCandidate = useCallback((): void => {
    if (closeCandidate == null) {
      return;
    }

    void (async () => {
      setIsCloseWithSavePending(true);
      const isSaved = await saveTab(closeCandidate);
      setIsCloseWithSavePending(false);

      if (!isSaved) {
        return;
      }

      closeTab(closeCandidate.path);
      setCloseCandidatePath(null);
    })();
  }, [closeCandidate, closeTab, saveTab]);

  return {
    closeCandidate,
    isCloseWithSavePending,
    requestCloseTab,
    requestCloseActiveTab,
    cancelCloseCandidate,
    closeCandidateWithoutSaving,
    saveAndCloseCandidate,
  };
}
