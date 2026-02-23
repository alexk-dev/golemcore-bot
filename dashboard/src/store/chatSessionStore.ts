import { create } from 'zustand';
import { createUuid } from '../utils/uuid';
import { isLegacyCompatibleConversationKey, normalizeConversationKey } from '../utils/conversationKey';

const CLIENT_INSTANCE_KEY = 'golem-chat-client-instance-id';
const ACTIVE_SESSION_KEY = 'golem-chat-session-id';

interface ChatSessionState {
  clientInstanceId: string;
  activeSessionId: string;
  setActiveSessionId: (sessionId: string) => void;
}

function readOrCreateStorageValue(storageKey: string): string {
  const stored = localStorage.getItem(storageKey);
  if (stored != null && stored.length > 0) {
    return stored;
  }
  const created = createUuid();
  localStorage.setItem(storageKey, created);
  return created;
}

function readOrCreateActiveSessionId(): string {
  const stored = normalizeConversationKey(localStorage.getItem(ACTIVE_SESSION_KEY));
  if (stored != null && isLegacyCompatibleConversationKey(stored)) {
    return stored;
  }
  const created = createUuid();
  localStorage.setItem(ACTIVE_SESSION_KEY, created);
  return created;
}

const initialClientInstanceId = readOrCreateStorageValue(CLIENT_INSTANCE_KEY);
const initialSessionId = readOrCreateActiveSessionId();

export const useChatSessionStore = create<ChatSessionState>((set) => ({
  clientInstanceId: initialClientInstanceId,
  activeSessionId: initialSessionId,
  setActiveSessionId: (sessionId: string) => {
    const normalized = normalizeConversationKey(sessionId);
    if (normalized == null || !isLegacyCompatibleConversationKey(normalized)) {
      const fallback = createUuid();
      localStorage.setItem(ACTIVE_SESSION_KEY, fallback);
      set({ activeSessionId: fallback });
      return;
    }
    localStorage.setItem(ACTIVE_SESSION_KEY, normalized);
    set({ activeSessionId: normalized });
  },
}));
