import type { TurnMetadata } from '../../store/contextPanelStore';
import type { ChatAttachmentPayload, OutboundChatPayload } from './chatInputTypes';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  model: string | null;
  tier: string | null;
  reasoning: string | null;
  clientStatus?: 'pending' | 'failed';
  outbound?: OutboundChatPayload;
  clientMessageId?: string | null;
  persisted: boolean;
}

export interface AssistantHint extends Partial<TurnMetadata> {
  model?: string | null;
  tier?: string | null;
  reasoning?: string | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  latencyMs?: number | null;
  maxContextTokens?: number | null;
}

export interface LiveProgressUpdate {
  type: 'intent' | 'summary';
  text: string;
  metadata?: Record<string, unknown>;
}

export interface SocketMessage {
  type?: string;
  eventType?: string;
  text?: string;
  sessionId?: string;
  hint?: AssistantHint;
  progressType?: 'intent' | 'summary' | 'clear';
  progressMetadata?: Record<string, unknown>;
  runtimeEventType?: string;
  runtimeEventTimestamp?: string;
  runtimeEventPayload?: Record<string, unknown>;
}

export interface ChatBindPayload {
  type: 'bind';
  sessionId: string;
  clientInstanceId: string;
}

export interface ChatSendPayload {
  text: string;
  attachments: ChatAttachmentPayload[];
  sessionId: string;
  clientInstanceId: string;
  clientMessageId: string;
}

export interface ChatRuntimeSessionState {
  sessionRecordId: string | null;
  messages: ChatMessage[];
  historyLoaded: boolean;
  historyLoading: boolean;
  historyError: string | null;
  hasMoreHistory: boolean;
  oldestLoadedMessageId: string | null;
  typing: boolean;
  running: boolean;
  progress: LiveProgressUpdate | null;
  turnMetadata: TurnMetadata;
}
