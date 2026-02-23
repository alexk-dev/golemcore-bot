import { useEffect } from 'react';
import { createNewTab, type IdeTabState } from '../store/ideStore';

export interface ContentPayload {
  path: string;
  content: string;
}

export interface UseSyncContentToTabsOptions {
  contentData: ContentPayload | undefined;
  openedTabs: IdeTabState[];
  upsertTab: (tab: IdeTabState) => void;
}

function findTabByPath(tabs: IdeTabState[], path: string): IdeTabState | undefined {
  return tabs.find((tab) => tab.path === path);
}

export function useSyncContentToTabs({
  contentData,
  openedTabs,
  upsertTab,
}: UseSyncContentToTabsOptions): void {
  useEffect(() => {
    // Synchronize loaded API content into tab state when file becomes active.
    if (contentData == null) {
      return;
    }

    const existing = findTabByPath(openedTabs, contentData.path);
    if (existing == null) {
      upsertTab(createNewTab(contentData.path, contentData.content));
      return;
    }

    if (!existing.isDirty && existing.savedContent !== contentData.content) {
      upsertTab({
        ...existing,
        content: contentData.content,
        savedContent: contentData.content,
        isDirty: false,
      });
    }
  }, [contentData, openedTabs, upsertTab]);
}

export interface UseGlobalIdeShortcutsOptions {
  onSave: () => void;
  onQuickOpen: () => void;
}

export function useGlobalIdeShortcuts({ onSave, onQuickOpen }: UseGlobalIdeShortcutsOptions): void {
  useEffect(() => {
    // Register global save/quick-open shortcuts for editor workflow.
    const onKeyDown = (event: KeyboardEvent): void => {
      const key = event.key.toLowerCase();
      const withPrimaryModifier = event.ctrlKey || event.metaKey;

      if (withPrimaryModifier && key === 's') {
        event.preventDefault();
        onSave();
        return;
      }

      if (withPrimaryModifier && key === 'p') {
        event.preventDefault();
        onQuickOpen();
      }
    };

    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [onQuickOpen, onSave]);
}

export function useBeforeUnloadGuard(isEnabled: boolean): void {
  useEffect(() => {
    // Warn before browser tab close/reload when user has unsaved changes.
    const onBeforeUnload = (event: BeforeUnloadEvent): void => {
      if (!isEnabled) {
        return;
      }
      event.preventDefault();
    };

    window.addEventListener('beforeunload', onBeforeUnload);
    return () => {
      window.removeEventListener('beforeunload', onBeforeUnload);
    };
  }, [isEnabled]);
}
