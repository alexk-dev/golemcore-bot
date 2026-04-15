import type {
  AssistantHint,
  ChatMessage,
  ChatRuntimeSessionState,
  OpenedTabContext,
} from '../components/chat/chatRuntimeTypes';
import type { OutboundChatPayload } from '../components/chat/chatInputTypes';
import { useIdeStore } from './ideStore';
import {
  applyAssistantTextUpdate,
  applyLiveProgressUpdate,
  patchTurnMetadata,
} from './chatRuntimeStoreUtils';
import type { ChatRuntimeGet, ChatRuntimeSet, ChatRuntimeState } from './chatRuntimeStore';

interface SendMessageArgs {
  sessionId: string;
  clientInstanceId: string;
  clientMessageId: string;
  payload: OutboundChatPayload;
}

interface AssistantTextArgs {
  sessionId: string;
  text: string;
  hint: AssistantHint | null;
  attachments: ChatMessage['attachments'];
  isFinal: boolean;
}

function ensureSessionState(
  sessions: Record<string, ChatRuntimeSessionState>,
  sessionId: string,
): ChatRuntimeSessionState {
  return sessions[sessionId] ?? {
    sessionRecordId: null,
    messages: [],
    historyLoaded: false,
    historyLoading: false,
    historyError: null,
    hasMoreHistory: false,
    oldestLoadedMessageId: null,
    typing: false,
    running: false,
    progress: null,
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

function cloneSessions(
  sessions: Record<string, ChatRuntimeSessionState>,
  sessionId: string,
  nextSession: ChatRuntimeSessionState,
): Record<string, ChatRuntimeSessionState> {
  return { ...sessions, [sessionId]: nextSession };
}

export function createMessageActions(
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
    appendOptimisticUserMessage: (sessionId, message) => setSession(set, sessionId, (current) => ({
      ...current,
      messages: [...current.messages, message],
      running: true,
    })),
    retryUserMessage: (sessionId, messageId) => retryUserMessage(set, sessionId, messageId),
    sendMessage: (sessionId, clientInstanceId, clientMessageId, payload) => sendMessage(get, set, { sessionId, clientInstanceId, clientMessageId, payload }),
    stopSession: (sessionId, clientInstanceId) => get().transport?.stop(sessionId, clientInstanceId) ?? false,
    markFirstPendingAsSent: (sessionId) => markFirstPendingAsSent(set, sessionId),
    markMessageAsFailed: (sessionId, messageId) => markMessageAsFailed(set, sessionId, messageId),
    markPendingMessagesAsFailed: () => markPendingMessagesAsFailed(set),
    setTyping: (sessionId, typing) => setSession(set, sessionId, (current) => ({ ...current, typing, running: typing || current.running })),
    setRunning: (sessionId, running) => setSession(set, sessionId, (current) => ({ ...current, running, typing: running ? current.typing : false })),
    applyProgressUpdate: (sessionId, progress) => setSession(set, sessionId, (current) => ({ ...current, ...applyLiveProgressUpdate(current, progress) })),
    applyTurnMetadataPatch: (sessionId, hint) => setSession(set, sessionId, (current) => ({ ...current, turnMetadata: patchTurnMetadata(current.turnMetadata, hint) })),
    applyAssistantText: (sessionId, text, hint, attachments, isFinal) => applyAssistantText(set, { sessionId, text, hint, attachments, isFinal }),
  };
}

function setSession(
  set: ChatRuntimeSet,
  sessionId: string,
  updater: (current: ChatRuntimeSessionState) => ChatRuntimeSessionState,
): void {
  set((state) => {
    const current = ensureSessionState(state.sessions, sessionId);
    return { sessions: cloneSessions(state.sessions, sessionId, updater(current)) };
  });
}

function retryUserMessage(set: ChatRuntimeSet, sessionId: string, messageId: string): OutboundChatPayload | null {
  let outbound: OutboundChatPayload | null = null;
  setSession(set, sessionId, (current) => {
    const messages = current.messages.map((message) => {
      if (message.id !== messageId || message.role !== 'user' || message.outbound == null) {
        return message;
      }
      outbound = message.outbound;
      return { ...message, clientStatus: 'pending' as const };
    });
    return { ...current, messages, running: outbound != null ? true : current.running };
  });
  return outbound;
}

function sendMessage(get: ChatRuntimeGet, set: ChatRuntimeSet, args: SendMessageArgs): boolean {
  const transport = get().transport;
  if (transport == null) {
    get().markMessageAsFailed(args.sessionId, args.clientMessageId);
    return false;
  }
  const ide = useIdeStore.getState();
  const openedTabs: OpenedTabContext[] = ide.openedTabs.map((tab) => ({
    path: tab.path,
    title: tab.title,
    isDirty: tab.isDirty,
  }));
  const sent = transport.sendMessage({
    text: args.payload.text,
    attachments: args.payload.attachments,
    sessionId: args.sessionId,
    clientInstanceId: args.clientInstanceId,
    clientMessageId: args.clientMessageId,
    openedTabs,
    activePath: ide.activePath,
  });
  if (!sent) {
    get().markMessageAsFailed(args.sessionId, args.clientMessageId);
    return false;
  }
  setSession(set, args.sessionId, (current) => ({ ...current, running: true }));
  return true;
}

function markFirstPendingAsSent(set: ChatRuntimeSet, sessionId: string): void {
  set((state) => {
    const current = ensureSessionState(state.sessions, sessionId);
    const targetIndex = current.messages.findIndex((message) => message.role === 'user' && message.clientStatus === 'pending');
    if (targetIndex < 0) {
      return state;
    }
    const messages = [...current.messages];
    messages[targetIndex] = { ...messages[targetIndex], clientStatus: undefined, outbound: undefined };
    return { sessions: cloneSessions(state.sessions, sessionId, { ...current, messages }) };
  });
}

function markMessageAsFailed(set: ChatRuntimeSet, sessionId: string, messageId: string): void {
  setSession(set, sessionId, (current) => ({
    ...current,
    messages: current.messages.map((message) => (message.role === 'user' && message.id === messageId ? { ...message, clientStatus: 'failed' as const } : message)),
    running: false,
  }));
}

function markPendingMessagesAsFailed(set: ChatRuntimeSet): void {
  set((state) => {
    let changed = false;
    const nextSessions = { ...state.sessions };
    for (const [sessionId, current] of Object.entries(state.sessions)) {
      const messages = current.messages.map((message) => {
        if (message.role !== 'user' || message.clientStatus !== 'pending') {
          return message;
        }
        changed = true;
        return { ...message, clientStatus: 'failed' as const };
      });
      if (changed || current.running || current.typing) {
        nextSessions[sessionId] = { ...current, messages, running: false, typing: false };
      }
    }
    return changed ? { sessions: nextSessions } : state;
  });
}

function applyAssistantText(set: ChatRuntimeSet, args: AssistantTextArgs): void {
  setSession(set, args.sessionId, (current) => ({
    ...current,
    ...applyAssistantTextUpdate(current, args),
  }));
}
