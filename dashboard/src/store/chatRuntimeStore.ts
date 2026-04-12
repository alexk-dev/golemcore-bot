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
  createEmptySessionState,
  dedupeMessages,
  mergeInitialHistory,
} from './chatRuntimeStoreUtils';
import { createMessageActions } from './chatRuntimeMessageActions';

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

export interface ChatRuntimeState {
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
  applyAssistantText: (
    sessionId: string,
    text: string,
    hint: AssistantHint | null,
    attachments: ChatMessage['attachments'],
    isFinal: boolean,
  ) => void;
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

export type ChatRuntimeSet = (
  partial:
    | ChatRuntimeState
    | Partial<ChatRuntimeState>
    | ((state: ChatRuntimeState) => ChatRuntimeState | Partial<ChatRuntimeState>),
) => void;

export type ChatRuntimeGet = () => ChatRuntimeState;

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
        return { sessions: cloneSessions(state.sessions, sessionId, { ...current, sessionRecordId }) };
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
        return { sessions: cloneSessions(state.sessions, sessionId, { ...current, historyLoading: false, historyError: error }) };
      }),
    hydrateHistory: ({ sessionId, sessionRecordId, messages, hasMoreHistory, oldestLoadedMessageId }) =>
      set((state) => {
        const current = ensureSessionState(state.sessions, sessionId);
        return {
          sessions: cloneSessions(state.sessions, sessionId, {
            ...current,
            sessionRecordId,
            messages: mergeInitialHistory(current.messages, messages),
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
        return {
          sessions: cloneSessions(state.sessions, sessionId, {
            ...current,
            sessionRecordId,
            messages: dedupeMessages([...messages, ...current.messages]),
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
