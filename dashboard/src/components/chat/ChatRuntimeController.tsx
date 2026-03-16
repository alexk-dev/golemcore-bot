import { useCallback, useEffect, useRef, useState, type ReactElement } from 'react';
import { setActiveSession } from '../../api/sessions';
import { useAuthStore } from '../../store/authStore';
import { useChatRuntimeStore } from '../../store/chatRuntimeStore';
import { useChatSessionStore } from '../../store/chatSessionStore';
import { useContextPanelStore } from '../../store/contextPanelStore';
import { createEmptyTurnMetadata } from '../../store/chatRuntimeStoreUtils';
import { isLegacyCompatibleConversationKey } from '../../utils/conversationKey';
import { useBootstrapActiveSession, useChatSocketTransport, useSocketMessageHandler } from './chatRuntimeControllerHooks';

const EMPTY_TURN_METADATA = createEmptyTurnMetadata();

export function ChatRuntimeController(): ReactElement | null {
  const token = useAuthStore((state) => state.accessToken);
  const clientInstanceId = useChatSessionStore((state) => state.clientInstanceId);
  const activeSessionId = useChatSessionStore((state) => state.activeSessionId);
  const setActiveSessionId = useChatSessionStore((state) => state.setActiveSessionId);
  const activeTurnMetadata = useChatRuntimeStore((state) => state.sessions[activeSessionId]?.turnMetadata);
  const setConnectionState = useChatRuntimeStore((state) => state.setConnectionState);
  const registerTransport = useChatRuntimeStore((state) => state.registerTransport);
  const clearTransport = useChatRuntimeStore((state) => state.clearTransport);
  const resetAllRuntime = useChatRuntimeStore((state) => state.resetAll);
  const markFirstPendingAsSent = useChatRuntimeStore((state) => state.markFirstPendingAsSent);
  const markPendingMessagesAsFailed = useChatRuntimeStore((state) => state.markPendingMessagesAsFailed);
  const setTyping = useChatRuntimeStore((state) => state.setTyping);
  const setRunning = useChatRuntimeStore((state) => state.setRunning);
  const applyTurnMetadataPatch = useChatRuntimeStore((state) => state.applyTurnMetadataPatch);
  const applyAssistantText = useChatRuntimeStore((state) => state.applyAssistantText);
  const setTurnMetadata = useContextPanelStore((state) => state.setTurnMetadata);

  const activeSessionIdRef = useRef(activeSessionId);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const typingTimersRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());
  const [activeSessionBootstrapped, setActiveSessionBootstrapped] = useState(false);

  activeSessionIdRef.current = activeSessionId;

  const clearReconnectTimer = useCallback((): void => {
    if (reconnectTimerRef.current != null) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
  }, []);

  const clearTypingTimer = useCallback((sessionId: string): void => {
    const timer = typingTimersRef.current.get(sessionId);
    if (timer != null) {
      clearTimeout(timer);
      typingTimersRef.current.delete(sessionId);
    }
  }, []);

  const clearAllTypingTimers = useCallback((): void => {
    for (const timer of typingTimersRef.current.values()) {
      clearTimeout(timer);
    }
    typingTimersRef.current.clear();
  }, []);

  const syncActiveSessionPointer = useCallback((sessionId: string): void => {
    if (!isLegacyCompatibleConversationKey(sessionId)) {
      return;
    }
    setActiveSession({
      channelType: 'web',
      clientInstanceId,
      conversationKey: sessionId,
    }).catch((error: unknown) => {
      console.error('Failed to persist active chat session pointer.', error);
    });
  }, [clientInstanceId]);

  const sendBind = useCallback((sessionId: string): boolean => {
    const transport = useChatRuntimeStore.getState().transport;
    if (transport == null || !isLegacyCompatibleConversationKey(sessionId)) {
      return false;
    }
    return transport.sendBind({
      type: 'bind',
      sessionId,
      clientInstanceId,
    });
  }, [clientInstanceId]);

  const handleSocketMessage = useSocketMessageHandler({
    activeSessionIdRef,
    typingTimersRef,
    markFirstPendingAsSent,
    setRunning,
    setTyping,
    clearTypingTimer,
    applyTurnMetadataPatch,
    setTurnMetadata,
    applyAssistantText,
  });

  useBootstrapActiveSession(
    clientInstanceId,
    activeSessionIdRef,
    setActiveSessionId,
    setActiveSessionBootstrapped,
  );

  useEffect(() => {
    // Keep persisted active session pointer aligned with the local selection.
    if (!activeSessionBootstrapped) {
      return;
    }
    syncActiveSessionPointer(activeSessionId);
  }, [activeSessionBootstrapped, activeSessionId, syncActiveSessionPointer]);

  useEffect(() => {
    // Keep the context panel telemetry aligned with the selected session across route changes.
    setTurnMetadata(activeTurnMetadata ?? EMPTY_TURN_METADATA);
  }, [activeTurnMetadata, setTurnMetadata]);

  useChatSocketTransport({
    token,
    activeSessionIdRef,
    reconnectTimerRef,
    clearReconnectTimer,
    clearAllTypingTimers,
    clearTransport,
    markPendingMessagesAsFailed,
    registerTransport,
    resetAllRuntime,
    setConnectionState,
    sendBind,
    handleSocketMessage,
  });

  useEffect(() => {
    // Re-bind the current conversation whenever selection changes on an already-open websocket.
    sendBind(activeSessionId);
  }, [activeSessionId, sendBind]);

  return null;
}
