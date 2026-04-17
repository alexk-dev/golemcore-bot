import { create } from 'zustand';
import { createUuid } from '../utils/uuid';
import { isLegacyCompatibleConversationKey, normalizeConversationKey } from '../utils/conversationKey';

const CLIENT_INSTANCE_KEY = 'golem-chat-client-instance-id';
const ACTIVE_SESSION_KEY = 'golem-chat-session-id';
const OPEN_SESSIONS_KEY = 'golem-chat-open-sessions';
const MEMORY_PRESET_OVERRIDES_KEY = 'golem-chat-memory-preset-overrides';

// Chat memory overrides are intentionally session-scoped. A missing entry means
// the chat inherits the global memory preset from backend preferences.
interface ChatSessionState {
  clientInstanceId: string;
  activeSessionId: string;
  openSessionIds: string[];
  memoryPresetOverrides: Record<string, string>;
  setActiveSessionId: (sessionId: string) => void;
  openSession: (sessionId: string) => void;
  closeSession: (sessionId: string) => void;
  startNewSession: () => string;
  setMemoryPresetOverride: (sessionId: string, preset: string) => void;
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

function persistMemoryPresetOverrides(overrides: Record<string, string>): void {
  localStorage.setItem(MEMORY_PRESET_OVERRIDES_KEY, JSON.stringify(overrides));
}

function sanitizeSessionId(sessionId: string): string | null {
  const normalized = normalizeConversationKey(sessionId);
  if (normalized == null || !isLegacyCompatibleConversationKey(normalized)) {
    return null;
  }
  return normalized;
}

function normalizeMemoryPresetOverride(preset: string | null | undefined): string | null {
  if (preset == null) {
    return null;
  }
  const normalized = preset.trim().toLowerCase();
  return normalized.length > 0 ? normalized : null;
}

export function normalizeStoredMemoryPresetOverrides(
  raw: string | null,
  openSessionIds: string[],
): Record<string, string> {
  if (raw == null || raw.length === 0) {
    return {};
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (error) {
    console.error('Failed to parse chat memory preset overrides from storage', error);
    return {};
  }

  if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return {};
  }

  const openSessionIdSet = new Set(openSessionIds);
  const normalizedOverrides: Record<string, string> = {};
  for (const [rawSessionId, rawPreset] of Object.entries(parsed)) {
    if (typeof rawPreset !== 'string') {
      continue;
    }
    const sessionId = sanitizeSessionId(rawSessionId);
    const preset = normalizeMemoryPresetOverride(rawPreset);
    if (sessionId != null && preset != null && openSessionIdSet.has(sessionId)) {
      normalizedOverrides[sessionId] = preset;
    }
  }
  return normalizedOverrides;
}

function readMemoryPresetOverrides(openSessionIds: string[]): Record<string, string> {
  const raw = localStorage.getItem(MEMORY_PRESET_OVERRIDES_KEY);
  const normalized = normalizeStoredMemoryPresetOverrides(raw, openSessionIds);
  persistMemoryPresetOverrides(normalized);
  return normalized;
}

function withoutMemoryPresetOverride(
  overrides: Record<string, string>,
  sessionId: string,
): Record<string, string> {
  const { [sessionId]: _removed, ...remaining } = overrides;
  return remaining;
}

const initialClientInstanceId = readOrCreateStorageValue(CLIENT_INSTANCE_KEY);
const initialSessionId = readOrCreateActiveSessionId();
const initialOpenSessionIds = readOpenSessionIds(initialSessionId);
const initialMemoryPresetOverrides = readMemoryPresetOverrides(initialOpenSessionIds);

export const useChatSessionStore = create<ChatSessionState>((set, get) => ({
  clientInstanceId: initialClientInstanceId,
  activeSessionId: initialSessionId,
  openSessionIds: initialOpenSessionIds,
  memoryPresetOverrides: initialMemoryPresetOverrides,

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
      // Closing a chat also drops its memory override so a later reused id cannot
      // inherit stale per-chat behavior from localStorage.
      const nextOverrides = withoutMemoryPresetOverride(get().memoryPresetOverrides, normalized);
      persistActiveSessionId(replacement);
      persistOpenSessionIds(nextOpen);
      persistMemoryPresetOverrides(nextOverrides);
      set({ activeSessionId: replacement, openSessionIds: nextOpen, memoryPresetOverrides: nextOverrides });
      return;
    }
    const wasActive = activeSessionId === normalized;
    const nextActive = wasActive
      ? remaining[Math.min(closingIndex, remaining.length - 1)]
      : activeSessionId;
    // The override belongs to the closed chat, not to its neighboring replacement.
    const nextOverrides = withoutMemoryPresetOverride(get().memoryPresetOverrides, normalized);
    persistActiveSessionId(nextActive);
    persistOpenSessionIds(remaining);
    persistMemoryPresetOverrides(nextOverrides);
    set({ activeSessionId: nextActive, openSessionIds: remaining, memoryPresetOverrides: nextOverrides });
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

  setMemoryPresetOverride: (sessionId: string, preset: string): void => {
    const normalizedSessionId = sanitizeSessionId(sessionId);
    if (normalizedSessionId == null || !get().openSessionIds.includes(normalizedSessionId)) {
      return;
    }

    const normalizedPreset = normalizeMemoryPresetOverride(preset);
    const current = get().memoryPresetOverrides;
    const nextOverrides = normalizedPreset == null
      ? withoutMemoryPresetOverride(current, normalizedSessionId)
      : { ...current, [normalizedSessionId]: normalizedPreset };
    persistMemoryPresetOverrides(nextOverrides);
    set({ memoryPresetOverrides: nextOverrides });
  },
}));
