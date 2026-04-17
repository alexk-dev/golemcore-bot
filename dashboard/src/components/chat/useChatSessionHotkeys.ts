import { useEffect } from 'react';
import { useChatSessionStore } from '../../store/chatSessionStore';

const DIGIT_PATTERN = /^[1-9]$/;
const EDITABLE_TAG_NAMES = new Set(['INPUT', 'TEXTAREA', 'SELECT']);

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  if (EDITABLE_TAG_NAMES.has(target.tagName)) {
    return true;
  }
  if (target.isContentEditable) {
    return true;
  }
  return target.getAttribute('contenteditable') === 'true';
}

export function useChatSessionHotkeys(): void {
  useEffect(() => {
    // Registers global Alt-based shortcuts for chat session tabs (new / close / switch).
    const handleKeyDown = (event: KeyboardEvent): void => {
      if (!event.altKey) {
        return;
      }
      if (isEditableTarget(event.target)) {
        return;
      }
      const store = useChatSessionStore.getState();
      const key = event.key.toLowerCase();

      if (key === 'n') {
        event.preventDefault();
        store.startNewSession();
        return;
      }

      if (key === 'w') {
        event.preventDefault();
        store.closeSession(store.activeSessionId);
        return;
      }

      if (DIGIT_PATTERN.test(event.key)) {
        const index = Number.parseInt(event.key, 10) - 1;
        const target = store.openSessionIds[index];
        if (target != null) {
          event.preventDefault();
          store.openSession(target);
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, []);
}
