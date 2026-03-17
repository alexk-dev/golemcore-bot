import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ChatMessage, ChatRuntimeSessionState } from './chatRuntimeTypes';
import { getSessionMessages, resolveSession } from '../../api/sessions';
import { useChatRuntimeStore } from '../../store/chatRuntimeStore';
import { isLegacyCompatibleConversationKey } from '../../utils/conversationKey';

const INITIAL_MESSAGES_LIMIT = 50;
const LOAD_MORE_MESSAGES_LIMIT = 50;

interface UseChatSessionHistoryResult {
  sessionState: ChatRuntimeSessionState;
  loadEarlierMessages: () => Promise<boolean>;
  reloadHistory: () => void;
}

interface PersistedHistoryState {
  historyLoaded: boolean;
  sessionRecordId: string | null;
  messageCount: number;
  running: boolean;
  typing: boolean;
}

export function shouldLoadPersistedHistory(
  sessionId: string,
  persistedHistoryState: PersistedHistoryState,
  reloadTick: number,
): boolean {
  if (!isLegacyCompatibleConversationKey(sessionId)) {
    return false;
  }
  if (reloadTick > 0) {
    return true;
  }
  if (!persistedHistoryState.historyLoaded) {
    return true;
  }
  if (persistedHistoryState.sessionRecordId != null) {
    return false;
  }
  return persistedHistoryState.messageCount > 0
    || persistedHistoryState.running
    || persistedHistoryState.typing;
}

function createFallbackSessionState(): ChatRuntimeSessionState {
  return {
    sessionRecordId: null,
    messages: [],
    historyLoaded: false,
    historyLoading: false,
    historyError: null,
    hasMoreHistory: false,
    oldestLoadedMessageId: null,
    typing: false,
    running: false,
    turnMetadata: {
      model: null,
      tier: null,
      reasoning: null,
      inputTokens: null,
      outputTokens: null,
      totalTokens: null,
      latencyMs: null,
      maxContextTokens: null,
      fileChanges: [],
    },
  };
}

function toChatMessage(message: Awaited<ReturnType<typeof getSessionMessages>>['messages'][number]): ChatMessage {
  return {
    id: message.id,
    role: message.role === 'assistant' ? 'assistant' : 'user',
    content: message.content ?? '',
    model: message.model,
    tier: message.modelTier,
    skill: message.skill,
    reasoning: message.reasoning,
    clientMessageId: message.clientMessageId,
    persisted: true,
  };
}

function isNotFoundError(error: unknown): boolean {
  if (typeof error !== 'object' || error == null) {
    return false;
  }
  if (!('response' in error)) {
    return false;
  }
  const response = (error as { response?: { status?: number } }).response;
  return response?.status === 404;
}

export function useChatSessionHistory(sessionId: string): UseChatSessionHistoryResult {
  const [reloadTick, setReloadTick] = useState(0);
  const sessionState = useChatRuntimeStore((state) => state.sessions[sessionId]);
  const registerSessionRecord = useChatRuntimeStore((state) => state.registerSessionRecord);
  const setHistoryLoading = useChatRuntimeStore((state) => state.setHistoryLoading);
  const setHistoryError = useChatRuntimeStore((state) => state.setHistoryError);
  const hydrateHistory = useChatRuntimeStore((state) => state.hydrateHistory);
  const prependHistory = useChatRuntimeStore((state) => state.prependHistory);

  const safeSessionState = useMemo(
    () => sessionState ?? createFallbackSessionState(),
    [sessionState],
  );
  const persistedHistoryState = useMemo<PersistedHistoryState>(() => ({
    historyLoaded: safeSessionState.historyLoaded,
    sessionRecordId: safeSessionState.sessionRecordId,
    messageCount: safeSessionState.messages.length,
    running: safeSessionState.running,
    typing: safeSessionState.typing,
  }), [
    safeSessionState.historyLoaded,
    safeSessionState.messages.length,
    safeSessionState.running,
    safeSessionState.sessionRecordId,
    safeSessionState.typing,
  ]);
  const shouldLoadHistory = useMemo(
    () => shouldLoadPersistedHistory(sessionId, persistedHistoryState, reloadTick),
    [
      persistedHistoryState,
      reloadTick,
      sessionId,
    ],
  );

  const reloadHistory = useCallback((): void => {
    setReloadTick((value) => value + 1);
  }, []);

  const loadEarlierMessages = useCallback(async (): Promise<boolean> => {
    if (!isLegacyCompatibleConversationKey(sessionId)) {
      return false;
    }
    if (safeSessionState.historyLoading || !safeSessionState.hasMoreHistory || safeSessionState.sessionRecordId == null) {
      return false;
    }

    setHistoryLoading(sessionId, true);
    try {
      const page = await getSessionMessages(
        safeSessionState.sessionRecordId,
        LOAD_MORE_MESSAGES_LIMIT,
        safeSessionState.oldestLoadedMessageId,
      );
      prependHistory({
        sessionId,
        sessionRecordId: safeSessionState.sessionRecordId,
        messages: page.messages.map(toChatMessage),
        hasMoreHistory: page.hasMore,
        oldestLoadedMessageId: page.oldestMessageId,
      });
      return true;
    } catch {
      setHistoryError(sessionId, 'Failed to load earlier messages.');
      return false;
    }
  }, [
    prependHistory,
    safeSessionState.hasMoreHistory,
    safeSessionState.historyLoading,
    safeSessionState.oldestLoadedMessageId,
    safeSessionState.sessionRecordId,
    sessionId,
    setHistoryError,
    setHistoryLoading,
  ]);

  useEffect(() => {
    // Load the latest persisted chat page once per conversation and on explicit retries.
    if (!shouldLoadHistory) {
      return;
    }

    let cancelled = false;
    setHistoryLoading(sessionId, true);

    resolveSession('web', sessionId)
      .then(async (summary) => {
        if (cancelled) {
          return;
        }

        registerSessionRecord(sessionId, summary.id);
        const page = await getSessionMessages(summary.id, INITIAL_MESSAGES_LIMIT);
        if (cancelled) {
          return;
        }

        hydrateHistory({
          sessionId,
          sessionRecordId: summary.id,
          messages: page.messages.map(toChatMessage),
          hasMoreHistory: page.hasMore,
          oldestLoadedMessageId: page.oldestMessageId,
        });
      })
      .catch((error: unknown) => {
        if (cancelled) {
          return;
        }
        if (isNotFoundError(error)) {
          hydrateHistory({
            sessionId,
            sessionRecordId: null,
            messages: [],
            hasMoreHistory: false,
            oldestLoadedMessageId: null,
          });
          return;
        }
        setHistoryError(sessionId, 'Failed to load chat history.');
      });

    return () => {
      cancelled = true;
    };
  }, [
    hydrateHistory,
    safeSessionState.messages.length,
    registerSessionRecord,
    reloadTick,
    safeSessionState.historyLoaded,
    safeSessionState.running,
    safeSessionState.sessionRecordId,
    safeSessionState.typing,
    sessionId,
    setHistoryError,
    setHistoryLoading,
    shouldLoadHistory,
  ]);

  return {
    sessionState: safeSessionState,
    loadEarlierMessages,
    reloadHistory,
  };
}
