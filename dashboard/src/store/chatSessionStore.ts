import { create } from 'zustand';
import { createUuid } from '../utils/uuid';
import { isLegacyCompatibleConversationKey, normalizeConversationKey } from '../utils/conversationKey';

const CLIENT_INSTANCE_KEY = 'golem-chat-client-instance-id';
const ACTIVE_SESSION_KEY = 'golem-chat-session-id';
const OPEN_SESSIONS_KEY = 'golem-chat-open-sessions';

interface ChatSessionState {
  clientInstanceId: string;
  activeSessionId: string;
  openSessionIds: string[];
  setActiveSessionId: (sessionId: string) => void;
  openSession: (sessionId: string) => void;
  closeSession: (sessionId: string) => void;
  startNewSession: () => string;
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

export function normalizeStoredOpenSessionIds(
  raw: string | null,
  fallbackActiveId: string,
): string[] {
  if (raw == null || raw.length === 0) {
    return [fallbackActiveId];
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (error) {
    console.error('Failed to parse open chat sessions from storage', error);
    return [fallbackActiveId];
  }
  if (!Array.isArray(parsed)) {
    return [fallbackActiveId];
  }
  const normalized: string[] = [];
  for (const entry of parsed) {
    if (typeof entry !== 'string') {
      continue;
    }
    const key = normalizeConversationKey(entry);
    if (key != null && isLegacyCompatibleConversationKey(key) && !normalized.includes(key)) {
      normalized.push(key);
    }
  }
  if (!normalized.includes(fallbackActiveId)) {
    normalized.push(fallbackActiveId);
  }
  return normalized;
}

function readOpenSessionIds(fallbackActiveId: string): string[] {
  const raw = localStorage.getItem(OPEN_SESSIONS_KEY);
  const normalized = normalizeStoredOpenSessionIds(raw, fallbackActiveId);
  localStorage.setItem(OPEN_SESSIONS_KEY, JSON.stringify(normalized));
  return normalized;
}

function persistActiveSessionId(sessionId: string): void {
  localStorage.setItem(ACTIVE_SESSION_KEY, sessionId);
}

function persistOpenSessionIds(openSessionIds: string[]): void {
  localStorage.setItem(OPEN_SESSIONS_KEY, JSON.stringify(openSessionIds));
}

function sanitizeSessionId(sessionId: string): string | null {
  const normalized = normalizeConversationKey(sessionId);
  if (normalized == null || !isLegacyCompatibleConversationKey(normalized)) {
    return null;
  }
  return normalized;
}

const initialClientInstanceId = readOrCreateStorageValue(CLIENT_INSTANCE_KEY);
const initialSessionId = readOrCreateActiveSessionId();
const initialOpenSessionIds = readOpenSessionIds(initialSessionId);

export const useChatSessionStore = create<ChatSessionState>((set, get) => ({
  clientInstanceId: initialClientInstanceId,
  activeSessionId: initialSessionId,
  openSessionIds: initialOpenSessionIds,

  setActiveSessionId: (sessionId: string) => {
    const normalized = sanitizeSessionId(sessionId);
    if (normalized == null) {
      const fallback = createUuid();
      const current = get().openSessionIds;
      const nextOpen = current.includes(fallback) ? current : [...current, fallback];
      persistActiveSessionId(fallback);
      persistOpenSessionIds(nextOpen);
      set({ activeSessionId: fallback, openSessionIds: nextOpen });
      return;
    }
    const current = get().openSessionIds;
    const nextOpen = current.includes(normalized) ? current : [...current, normalized];
    persistActiveSessionId(normalized);
    persistOpenSessionIds(nextOpen);
    set({ activeSessionId: normalized, openSessionIds: nextOpen });
  },

  openSession: (sessionId: string) => {
    const normalized = sanitizeSessionId(sessionId);
    if (normalized == null) {
      return;
    }
    const current = get().openSessionIds;
    const nextOpen = current.includes(normalized) ? current : [...current, normalized];
    persistActiveSessionId(normalized);
    persistOpenSessionIds(nextOpen);
    set({ activeSessionId: normalized, openSessionIds: nextOpen });
  },

  closeSession: (sessionId: string) => {
    const normalized = sanitizeSessionId(sessionId);
    if (normalized == null) {
      return;
    }
    const { openSessionIds, activeSessionId } = get();
    const closingIndex = openSessionIds.indexOf(normalized);
    if (closingIndex === -1) {
      return;
    }
    const remaining = openSessionIds.filter((id) => id !== normalized);
    if (remaining.length === 0) {
      const replacement = createUuid();
      const nextOpen = [replacement];
      persistActiveSessionId(replacement);
      persistOpenSessionIds(nextOpen);
      set({ activeSessionId: replacement, openSessionIds: nextOpen });
      return;
    }
    const wasActive = activeSessionId === normalized;
    const nextActive = wasActive
      ? remaining[Math.min(closingIndex, remaining.length - 1)]
      : activeSessionId;
    persistActiveSessionId(nextActive);
    persistOpenSessionIds(remaining);
    set({ activeSessionId: nextActive, openSessionIds: remaining });
  },

  startNewSession: (): string => {
    const created = createUuid();
    const current = get().openSessionIds;
    const nextOpen = [...current, created];
    persistActiveSessionId(created);
    persistOpenSessionIds(nextOpen);
    set({ activeSessionId: created, openSessionIds: nextOpen });
    return created;
  },
}));
