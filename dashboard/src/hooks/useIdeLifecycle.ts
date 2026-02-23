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
  onCloseActiveTab: () => void;
  onActivatePreviousTab: () => void;
  onActivateNextTab: () => void;
}

function isEditableElement(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }

  if (target.isContentEditable) {
    return true;
  }

  const tagName = target.tagName.toLowerCase();
  return tagName === 'input' || tagName === 'textarea' || tagName === 'select';
}

export function useGlobalIdeShortcuts({
  onSave,
  onQuickOpen,
  onCloseActiveTab,
  onActivatePreviousTab,
  onActivateNextTab,
}: UseGlobalIdeShortcutsOptions): void {
  useEffect(() => {
    // Register global save/quick-open/tab shortcuts for editor workflow.
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
        return;
      }

      if (withPrimaryModifier && key === 'w') {
        event.preventDefault();
        onCloseActiveTab();
        return;
      }

      const isTabNavigationShortcut = event.altKey && (key === 'arrowleft' || key === 'arrowright');
      if (!isTabNavigationShortcut) {
        return;
      }

      if (isEditableElement(event.target)) {
        return;
      }

      event.preventDefault();
      if (key === 'arrowleft') {
        onActivatePreviousTab();
        return;
      }

      onActivateNextTab();
    };

    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [onActivateNextTab, onActivatePreviousTab, onCloseActiveTab, onQuickOpen, onSave]);
}

export function useBeforeUnloadGuard(hasDirtyTabs: boolean): void {
  useEffect(() => {
    // Warn user before browser refresh/close when there are unsaved tabs.
    const onBeforeUnload = (event: BeforeUnloadEvent): void => {
      if (!hasDirtyTabs) {
        return;
      }

      event.preventDefault();
    };

    window.addEventListener('beforeunload', onBeforeUnload);
    return () => {
      window.removeEventListener('beforeunload', onBeforeUnload);
    };
  }, [hasDirtyTabs]);
}
