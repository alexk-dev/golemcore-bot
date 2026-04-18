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

interface PrimaryShortcutHandlers {
  onSave: () => void;
  onQuickOpen: () => void;
  onCommandPalette: () => void;
  onCloseActiveTab: () => void;
}

function findTabByPath(tabs: IdeTabState[], path: string): IdeTabState | undefined {
  return tabs.find((tab) => tab.path === path);
}

function normalizeContent(contentData: ContentPayload): string {
  return contentData.content ?? '';
}

/**
 * Synchronizes freshly loaded file content from the API into the local IDE tab
 * store while preserving unsaved tab edits.
 */
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

function handlePrimaryModifierShortcut(
  event: KeyboardEvent,
  key: string,
  handlers: PrimaryShortcutHandlers,
): boolean {
  const withPrimaryModifier = event.ctrlKey || event.metaKey;
  if (!withPrimaryModifier) {
    return false;
  }

  if (key === 's') {
    event.preventDefault();
    handlers.onSave();
    return true;
  }

  if (key === 'p' && event.shiftKey) {
    event.preventDefault();
    handlers.onCommandPalette();
    return true;
  }

  if (key === 'p') {
    event.preventDefault();
    handlers.onQuickOpen();
    return true;
  }

  if (key === 'w') {
    event.preventDefault();
    handlers.onCloseActiveTab();
    return true;
  }

  return false;
}

function handleTabNavigationShortcut(
  event: KeyboardEvent,
  key: string,
  onActivatePreviousTab: () => void,
  onActivateNextTab: () => void,
): boolean {
  const isTabNavigationShortcut = event.altKey && (key === 'arrowleft' || key === 'arrowright');
  if (!isTabNavigationShortcut || isEditableElement(event.target)) {
    return false;
  }

  event.preventDefault();
  if (key === 'arrowleft') {
    onActivatePreviousTab();
    return true;
  }

  onActivateNextTab();
  return true;
}

/**
 * Registers global editor shortcuts for save, quick open, command palette, tab
 * close, and tab navigation.
 */
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
      const primaryHandlers: PrimaryShortcutHandlers = {
        onSave,
        onQuickOpen,
        onCommandPalette,
        onCloseActiveTab,
      };

      if (handlePrimaryModifierShortcut(event, key, primaryHandlers)) {
        return;
      }

      handleTabNavigationShortcut(event, key, onActivatePreviousTab, onActivateNextTab);
    };

    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [onActivateNextTab, onActivatePreviousTab, onCloseActiveTab, onCommandPalette, onQuickOpen, onSave]);
}

/**
 * Attaches a browser unload warning while the IDE contains unsaved tabs.
 */
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
