import type { TurnMetadata } from '../../store/contextPanelStore';
import type { ChatAttachmentPayload, OutboundChatPayload } from './chatInputTypes';

export interface ChatMessageAttachment {
  type: 'image' | 'document';
  name: string | null;
  mimeType: string | null;
  url: string | null;
  internalFilePath: string | null;
  thumbnailBase64: string | null;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  model: string | null;
  tier: string | null;
  skill: string | null;
  reasoning: string | null;
  attachments: ChatMessageAttachment[];
  clientStatus?: 'pending' | 'failed';
  outbound?: OutboundChatPayload;
  clientMessageId?: string | null;
  persisted: boolean;
}

export interface AssistantHint extends Partial<TurnMetadata> {
  model?: string | null;
  tier?: string | null;
  skill?: string | null;
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
  attachments?: ChatMessageAttachment[];
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

export interface OpenedTabContext {
  path: string;
  title: string;
  isDirty: boolean;
}

export interface ChatSendPayload {
  text: string;
  attachments: ChatAttachmentPayload[];
  sessionId: string;
  clientInstanceId: string;
  clientMessageId: string;
  memoryPreset?: string | null;
  openedTabs?: OpenedTabContext[];
  activePath?: string | null;
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
