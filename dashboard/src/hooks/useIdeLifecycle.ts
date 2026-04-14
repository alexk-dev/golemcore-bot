import { useEffect } from 'react';
import { createNewTab, type IdeTabState } from '../store/ideStore';

export interface ContentPayload {
  path: string;
  content: string | null;
  mimeType: string | null;
  binary: boolean;
  image: boolean;
  editable: boolean;
  downloadUrl: string | null;
}

export interface UseSyncContentToTabsOptions {
  contentData: ContentPayload | undefined;
  openedTabs: IdeTabState[];
  upsertTab: (tab: IdeTabState) => void;
}

function findTabByPath(tabs: IdeTabState[], path: string): IdeTabState | undefined {
  return tabs.find((tab) => tab.path === path);
}

function normalizeContent(contentData: ContentPayload): string {
  return contentData.content ?? '';
}

export function useSyncContentToTabs({
  contentData,
  openedTabs,
  upsertTab,
}: UseSyncContentToTabsOptions): void {
  useEffect(() => {
    // Synchronize loaded API content and metadata into tab state when file becomes active.
    if (contentData == null) {
      return;
    }

    const nextContent = normalizeContent(contentData);
    const metadata = {
      mimeType: contentData.mimeType,
      binary: contentData.binary,
      image: contentData.image,
      editable: contentData.editable,
      downloadUrl: contentData.downloadUrl,
    };
    const existing = findTabByPath(openedTabs, contentData.path);
    if (existing == null) {
      upsertTab(createNewTab(contentData.path, nextContent, metadata));
      return;
    }

    if (!existing.isDirty && existing.savedContent !== nextContent) {
      upsertTab({
        ...existing,
        ...metadata,
        content: nextContent,
        savedContent: nextContent,
        isDirty: false,
      });
    }
  }, [contentData, openedTabs, upsertTab]);
}

export interface UseGlobalIdeShortcutsOptions {
  onSave: () => void;
  onQuickOpen: () => void;
  onCommandPalette: () => void;
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
  onCommandPalette,
  onCloseActiveTab,
  onActivatePreviousTab,
  onActivateNextTab,
}: UseGlobalIdeShortcutsOptions): void {
  useEffect(() => {
    // Register global save/quick-open/command-palette/tab shortcuts for editor workflow.
    const onKeyDown = (event: KeyboardEvent): void => {
      const key = event.key.toLowerCase();
      const withPrimaryModifier = event.ctrlKey || event.metaKey;

      if (withPrimaryModifier && key === 's') {
        event.preventDefault();
        onSave();
        return;
      }

      if (withPrimaryModifier && key === 'p' && event.shiftKey) {
        event.preventDefault();
        onCommandPalette();
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
  }, [onActivateNextTab, onActivatePreviousTab, onCloseActiveTab, onCommandPalette, onQuickOpen, onSave]);
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
