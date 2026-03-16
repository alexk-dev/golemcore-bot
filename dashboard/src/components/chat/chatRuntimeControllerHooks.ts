import { useCallback, useEffect, useRef } from 'react';
import { getActiveSession } from '../../api/sessions';
import type { useChatRuntimeStore } from '../../store/chatRuntimeStore';
import type { TurnMetadata } from '../../store/contextPanelStore';
import { isLegacyCompatibleConversationKey, normalizeConversationKey } from '../../utils/conversationKey';
import type { AssistantHint, SocketMessage } from './chatRuntimeTypes';

const RECONNECT_DELAY_MS = 3000;
const TYPING_RESET_MS = 3000;

interface SocketMessageHandlerConfig {
  activeSessionIdRef: React.MutableRefObject<string>;
  typingTimersRef: React.MutableRefObject<Map<string, ReturnType<typeof setTimeout>>>;
  markFirstPendingAsSent: (sessionId: string) => void;
  setRunning: (sessionId: string, running: boolean) => void;
  setTyping: (sessionId: string, typing: boolean) => void;
  clearTypingTimer: (sessionId: string) => void;
  applyTurnMetadataPatch: (sessionId: string, hint: AssistantHint) => void;
  setTurnMetadata: (meta: Partial<TurnMetadata>) => void;
  applyAssistantText: (sessionId: string, text: string, hint: AssistantHint | null, isFinal: boolean) => void;
}

interface ChatSocketTransportConfig {
  token: string | null;
  activeSessionIdRef: React.MutableRefObject<string>;
  reconnectTimerRef: React.MutableRefObject<ReturnType<typeof setTimeout> | null>;
  clearReconnectTimer: () => void;
  clearAllTypingTimers: () => void;
  clearTransport: () => void;
  markPendingMessagesAsFailed: () => void;
  registerTransport: ReturnType<typeof useChatRuntimeStore.getState>['registerTransport'];
  resetAllRuntime: () => void;
  setConnectionState: (state: ReturnType<typeof useChatRuntimeStore.getState>['connectionState']) => void;
  sendBind: (sessionId: string) => boolean;
  handleSocketMessage: (data: SocketMessage) => void;
}

function isSessionRuntimeEventFinished(runtimeEventType: string | undefined): boolean {
  return runtimeEventType === 'TURN_FINISHED' || runtimeEventType === 'TURN_FAILED';
}

function isSessionRuntimeEventStarted(runtimeEventType: string | undefined): boolean {
  return runtimeEventType === 'TURN_STARTED' || runtimeEventType === 'LLM_STARTED' || runtimeEventType === 'RETRY_STARTED';
}

export function useBootstrapActiveSession(
  clientInstanceId: string,
  activeSessionIdRef: React.MutableRefObject<string>,
  setActiveSessionId: (sessionId: string) => void,
  setActiveSessionBootstrapped: (value: boolean) => void,
): void {
  useEffect(() => {
    // Resolve the authoritative active web conversation once per client instance.
    if (clientInstanceId.length === 0) {
      return;
    }

    let cancelled = false;
    const initialSessionId = activeSessionIdRef.current;

    getActiveSession('web', clientInstanceId)
      .then((activeSession) => {
        if (cancelled || activeSessionIdRef.current !== initialSessionId) {
          return;
        }

        const nextConversationKey = normalizeConversationKey(activeSession.conversationKey);
        if (nextConversationKey != null && isLegacyCompatibleConversationKey(nextConversationKey)) {
          setActiveSessionId(nextConversationKey);
        }
      })
      .catch((error: unknown) => {
        console.error('Failed to resolve active chat session.', error);
      })
      .finally(() => {
        if (!cancelled) {
          setActiveSessionBootstrapped(true);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [activeSessionIdRef, clientInstanceId, setActiveSessionBootstrapped, setActiveSessionId]);
}

export function useSocketMessageHandler({
  activeSessionIdRef,
  typingTimersRef,
  markFirstPendingAsSent,
  setRunning,
  setTyping,
  clearTypingTimer,
  applyTurnMetadataPatch,
  setTurnMetadata,
  applyAssistantText,
}: SocketMessageHandlerConfig): (data: SocketMessage) => void {
  return useCallback((data: SocketMessage): void => {
    const sessionId = normalizeConversationKey(data.sessionId);
    if (sessionId == null) {
      return;
    }

    if (data.type === 'system_event' && data.eventType === 'typing') {
      markFirstPendingAsSent(sessionId);
      setRunning(sessionId, true);
      setTyping(sessionId, true);
      clearTypingTimer(sessionId);
      typingTimersRef.current.set(sessionId, setTimeout(() => {
        setTyping(sessionId, false);
      }, TYPING_RESET_MS));
      return;
    }

    if (data.type === 'system_event' && data.eventType === 'runtime_event') {
      if (isSessionRuntimeEventStarted(data.runtimeEventType)) {
        setRunning(sessionId, true);
      }
      if (isSessionRuntimeEventFinished(data.runtimeEventType)) {
        clearTypingTimer(sessionId);
        setTyping(sessionId, false);
        setRunning(sessionId, false);
      }
      return;
    }

    if (data.type !== 'assistant_chunk' && data.type !== 'assistant_done') {
      return;
    }

    clearTypingTimer(sessionId);
    setTyping(sessionId, false);
    markFirstPendingAsSent(sessionId);

    const hint: AssistantHint | null = data.hint ?? null;
    if (hint != null) {
      applyTurnMetadataPatch(sessionId, hint);
      if (sessionId === activeSessionIdRef.current) {
        setTurnMetadata(hint);
      }
    }

    applyAssistantText(sessionId, data.text ?? '', hint, data.type === 'assistant_done');
  }, [
    activeSessionIdRef,
    applyAssistantText,
    applyTurnMetadataPatch,
    clearTypingTimer,
    markFirstPendingAsSent,
    setRunning,
    setTurnMetadata,
    setTyping,
    typingTimersRef,
  ]);
}

function createSocketPayloadSender(socket: WebSocket): (payload: object) => boolean {
  return (payload: object): boolean => {
    if (socket.readyState !== WebSocket.OPEN) {
      return false;
    }
    socket.send(JSON.stringify(payload));
    return true;
  };
}

export function useChatSocketTransport({
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
}: ChatSocketTransportConfig): void {
  const socketRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    // Maintain a single long-lived websocket transport across the protected dashboard shell.
    clearReconnectTimer();
    clearAllTypingTimers();

    if (token == null || token.length === 0) {
      socketRef.current?.close();
      socketRef.current = null;
      resetAllRuntime();
      return;
    }

    let disposed = false;

    const openSocket = (): void => {
      if (disposed) {
        return;
      }

      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      setConnectionState(socketRef.current == null ? 'connecting' : 'reconnecting');
      const socket = new WebSocket(`${protocol}//${window.location.host}/ws/chat?token=${token}`);
      const sendPayload = createSocketPayloadSender(socket);
      socketRef.current = socket;

      socket.onopen = () => {
        if (disposed) {
          socket.close();
          return;
        }

        clearReconnectTimer();
        setConnectionState('connected');
        registerTransport({
          sendBind: (payload) => sendPayload(payload),
          sendMessage: (payload) => sendPayload(payload),
          stop: (sessionId, clientInstanceId) => sendPayload({
            text: '/stop',
            sessionId,
            clientInstanceId,
          }),
        });
        sendBind(activeSessionIdRef.current);
      };

      socket.onclose = () => {
        if (disposed) {
          return;
        }

        markPendingMessagesAsFailed();
        clearTransport();
        setConnectionState('reconnecting');
        clearReconnectTimer();
        reconnectTimerRef.current = setTimeout(openSocket, RECONNECT_DELAY_MS);
      };

      socket.onmessage = (event: MessageEvent<string>) => {
        if (disposed) {
          return;
        }

        try {
          const data = JSON.parse(event.data) as SocketMessage;
          handleSocketMessage(data);
        } catch (error: unknown) {
          console.error('Failed to process chat websocket message.', error);
        }
      };
    };

    openSocket();

    return () => {
      disposed = true;
      clearReconnectTimer();
      clearAllTypingTimers();
      clearTransport();
      setConnectionState('disconnected');
      const socket = socketRef.current;
      socketRef.current = null;
      if (reconnectTimerRef.current != null) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      socket?.close();
    };
  }, [
    activeSessionIdRef,
    clearAllTypingTimers,
    clearReconnectTimer,
    clearTransport,
    markPendingMessagesAsFailed,
    handleSocketMessage,
    registerTransport,
    reconnectTimerRef,
    resetAllRuntime,
    sendBind,
    setConnectionState,
    token,
  ]);
}
