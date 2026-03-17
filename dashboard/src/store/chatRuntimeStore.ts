import { create } from 'zustand';
import type {
  ChatMessage,
  ChatRuntimeSessionState,
  AssistantHint,
  ChatBindPayload,
  ChatSendPayload,
  LiveProgressUpdate,
} from '../components/chat/chatRuntimeTypes';
import type { OutboundChatPayload } from '../components/chat/chatInputTypes';
import {
  applyAssistantTextUpdate,
  applyLiveProgressUpdate,
  createEmptySessionState,
  dedupeMessages,
  mergeInitialHistory,
  patchTurnMetadata,
} from './chatRuntimeStoreUtils';

export type ChatConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';

interface ChatTransport {
  sendBind: (payload: ChatBindPayload) => boolean;
  sendMessage: (payload: ChatSendPayload) => boolean;
  stop: (sessionId: string, clientInstanceId: string) => boolean;
}

interface HydrateHistoryPayload {
  sessionId: string;
  sessionRecordId: string | null;
  messages: ChatMessage[];
  hasMoreHistory: boolean;
  oldestLoadedMessageId: string | null;
}

interface ChatRuntimeState {
  connectionState: ChatConnectionState;
  sessions: Record<string, ChatRuntimeSessionState>;
  transport: ChatTransport | null;
  setConnectionState: (connectionState: ChatConnectionState) => void;
  registerTransport: (transport: ChatTransport) => void;
  clearTransport: () => void;
  resetAll: () => void;
  resetSession: (sessionId: string) => void;
  registerSessionRecord: (sessionId: string, sessionRecordId: string | null) => void;
  setHistoryLoading: (sessionId: string, loading: boolean) => void;
  setHistoryError: (sessionId: string, error: string | null) => void;
  hydrateHistory: (payload: HydrateHistoryPayload) => void;
  prependHistory: (payload: HydrateHistoryPayload) => void;
  appendOptimisticUserMessage: (sessionId: string, message: ChatMessage) => void;
  retryUserMessage: (sessionId: string, messageId: string) => OutboundChatPayload | null;
  sendMessage: (
    sessionId: string,
    clientInstanceId: string,
    clientMessageId: string,
    payload: OutboundChatPayload,
  ) => boolean;
  stopSession: (sessionId: string, clientInstanceId: string) => boolean;
  markFirstPendingAsSent: (sessionId: string) => void;
  markMessageAsFailed: (sessionId: string, messageId: string) => void;
  markPendingMessagesAsFailed: () => void;
  setTyping: (sessionId: string, typing: boolean) => void;
  setRunning: (sessionId: string, running: boolean) => void;
  applyProgressUpdate: (sessionId: string, progress: LiveProgressUpdate | null) => void;
  applyTurnMetadataPatch: (sessionId: string, hint: AssistantHint) => void;
  applyAssistantText: (sessionId: string, text: string, hint: AssistantHint | null, isFinal: boolean) => void;
}

function ensureSessionState(
  sessions: Record<string, ChatRuntimeSessionState>,
  sessionId: string,
): ChatRuntimeSessionState {
  return sessions[sessionId] ?? createEmptySessionState();
}

function cloneSessions(
  sessions: Record<string, ChatRuntimeSessionState>,
  sessionId: string,
  nextSession: ChatRuntimeSessionState,
): Record<string, ChatRuntimeSessionState> {
  return {
    ...sessions,
    [sessionId]: nextSession,
  };
}

type ChatRuntimeSet = (
  partial:
    | ChatRuntimeState
    | Partial<ChatRuntimeState>
    | ((state: ChatRuntimeState) => ChatRuntimeState | Partial<ChatRuntimeState>),
) => void;

type ChatRuntimeGet = () => ChatRuntimeState;

function createCoreActions(set: ChatRuntimeSet): Pick<
  ChatRuntimeState,
  'setConnectionState' | 'registerTransport' | 'clearTransport' | 'resetAll' | 'resetSession'
> {
  return {
  setConnectionState: (connectionState) => set({ connectionState }),
  registerTransport: (transport) => set({ transport }),
  clearTransport: () => set({ transport: null }),
  resetAll: () => set({ connectionState: 'disconnected', sessions: {}, transport: null }),
  resetSession: (sessionId) =>
    set((state) => ({
      sessions: cloneSessions(state.sessions, sessionId, createEmptySessionState()),
    })),
  };
}

function createHistoryActions(set: ChatRuntimeSet): Pick<
  ChatRuntimeState,
  'registerSessionRecord' | 'setHistoryLoading' | 'setHistoryError' | 'hydrateHistory' | 'prependHistory'
> {
  return {
  registerSessionRecord: (sessionId, sessionRecordId) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          sessionRecordId,
        }),
      };
    }),
  setHistoryLoading: (sessionId, loading) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          historyLoading: loading,
          historyError: loading ? null : current.historyError,
        }),
      };
    }),
  setHistoryError: (sessionId, error) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          historyLoading: false,
          historyError: error,
        }),
      };
    }),
  hydrateHistory: ({ sessionId, sessionRecordId, messages, hasMoreHistory, oldestLoadedMessageId }) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      const nextMessages = mergeInitialHistory(current.messages, messages);
      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          sessionRecordId,
          messages: nextMessages,
          historyLoaded: true,
          historyLoading: false,
          historyError: null,
          hasMoreHistory,
          oldestLoadedMessageId,
        }),
      };
    }),
  prependHistory: ({ sessionId, sessionRecordId, messages, hasMoreHistory, oldestLoadedMessageId }) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      const nextMessages = dedupeMessages([...messages, ...current.messages]);
      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          sessionRecordId,
          messages: nextMessages,
          historyLoaded: true,
          historyLoading: false,
          historyError: null,
          hasMoreHistory,
          oldestLoadedMessageId,
        }),
      };
    }),
  };
}

function createMessageActions(
  set: ChatRuntimeSet,
  get: ChatRuntimeGet,
): Pick<
  ChatRuntimeState,
  | 'appendOptimisticUserMessage'
  | 'retryUserMessage'
  | 'sendMessage'
  | 'stopSession'
  | 'markFirstPendingAsSent'
  | 'markMessageAsFailed'
  | 'markPendingMessagesAsFailed'
  | 'setTyping'
  | 'setRunning'
  | 'applyProgressUpdate'
  | 'applyTurnMetadataPatch'
  | 'applyAssistantText'
> {
  return {
  appendOptimisticUserMessage: (sessionId, message) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          messages: [...current.messages, message],
          running: true,
        }),
      };
    }),
  retryUserMessage: (sessionId, messageId) => {
    let outbound: OutboundChatPayload | null = null;

    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      const nextMessages = current.messages.map((message) => {
        if (message.id !== messageId || message.role !== 'user' || message.outbound == null) {
          return message;
        }
        outbound = message.outbound;
        return {
          ...message,
          clientStatus: 'pending' as const,
        };
      });

      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          messages: nextMessages,
          running: outbound != null ? true : current.running,
        }),
      };
    });

    return outbound;
  },
  sendMessage: (sessionId, clientInstanceId, clientMessageId, payload) => {
    const transport = get().transport;
    if (transport == null) {
      get().markMessageAsFailed(sessionId, clientMessageId);
      return false;
    }

    const sent = transport.sendMessage({
      text: payload.text,
      attachments: payload.attachments,
      sessionId,
      clientInstanceId,
      clientMessageId,
    });
    if (!sent) {
      get().markMessageAsFailed(sessionId, clientMessageId);
      return false;
    }

    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          running: true,
        }),
      };
    });
    return true;
  },
  stopSession: (sessionId, clientInstanceId) => {
    const transport = get().transport;
    if (transport == null) {
      return false;
    }
    return transport.stop(sessionId, clientInstanceId);
  },
  markFirstPendingAsSent: (sessionId) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      const targetIndex = current.messages.findIndex((message) => message.role === 'user' && message.clientStatus === 'pending');
      if (targetIndex < 0) {
        return state;
      }

      const nextMessages = [...current.messages];
      nextMessages[targetIndex] = {
        ...nextMessages[targetIndex],
        clientStatus: undefined,
        outbound: undefined,
        persisted: nextMessages[targetIndex].persisted,
      };

      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          messages: nextMessages,
        }),
      };
    }),
  markMessageAsFailed: (sessionId, messageId) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      const nextMessages = current.messages.map((message) => {
        if (message.role === 'user' && message.id === messageId) {
          return {
            ...message,
            clientStatus: 'failed' as const,
          };
        }
        return message;
      });

      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          messages: nextMessages,
          running: false,
        }),
      };
    }),
  markPendingMessagesAsFailed: () =>
    set((state) => {
      let changed = false;
      const nextSessions = { ...state.sessions };

      for (const [sessionId, current] of Object.entries(state.sessions)) {
        let sessionChanged = false;
        const nextMessages = current.messages.map((message) => {
          if (message.role !== 'user' || message.clientStatus !== 'pending') {
            return message;
          }
          sessionChanged = true;
          return {
            ...message,
            clientStatus: 'failed' as const,
          };
        });

        if (!sessionChanged && !current.running && !current.typing) {
          continue;
        }

        nextSessions[sessionId] = {
          ...current,
          messages: nextMessages,
          running: false,
          typing: false,
        };
        changed = true;
      }

      return changed ? { sessions: nextSessions } : state;
    }),
  setTyping: (sessionId, typing) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          typing,
          running: typing || current.running,
        }),
      };
    }),
  setRunning: (sessionId, running) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          running,
          typing: running ? current.typing : false,
        }),
      };
    }),
  applyProgressUpdate: (sessionId, progress) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      const nextSession = applyLiveProgressUpdate(current, progress);
      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          ...nextSession,
        }),
      };
    }),
  applyTurnMetadataPatch: (sessionId, hint) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          turnMetadata: patchTurnMetadata(current.turnMetadata, hint),
        }),
      };
    }),
  applyAssistantText: (sessionId, text, hint, isFinal) =>
    set((state) => {
      const current = ensureSessionState(state.sessions, sessionId);
      const nextSession = applyAssistantTextUpdate(current, sessionId, text, hint, isFinal);

      return {
        sessions: cloneSessions(state.sessions, sessionId, {
          ...current,
          ...nextSession,
        }),
      };
    }),
  };
}

function createChatRuntimeState(set: ChatRuntimeSet, get: ChatRuntimeGet): ChatRuntimeState {
  return {
    connectionState: 'disconnected',
    sessions: {},
    transport: null,
    ...createCoreActions(set),
    ...createHistoryActions(set),
    ...createMessageActions(set, get),
  };
}

export const useChatRuntimeStore = create<ChatRuntimeState>((set, get) => createChatRuntimeState(set, get));
