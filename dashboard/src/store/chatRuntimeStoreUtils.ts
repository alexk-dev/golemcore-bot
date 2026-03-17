import type { TurnMetadata } from './contextPanelStore';
import type { AssistantHint, ChatMessage, ChatRuntimeSessionState } from '../components/chat/chatRuntimeTypes';

const EMPTY_TURN_METADATA: TurnMetadata = {
  model: null,
  tier: null,
  reasoning: null,
  inputTokens: null,
  outputTokens: null,
  totalTokens: null,
  latencyMs: null,
  maxContextTokens: null,
  fileChanges: [],
};

export function createEmptyTurnMetadata(): TurnMetadata {
  return {
    ...EMPTY_TURN_METADATA,
    fileChanges: [],
  };
}

export function createEmptySessionState(): ChatRuntimeSessionState {
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
    turnMetadata: createEmptyTurnMetadata(),
  };
}

function messageIdentity(message: ChatMessage): string {
  if (message.role === 'user' && message.clientMessageId != null && message.clientMessageId.length > 0) {
    return `user:${message.clientMessageId}`;
  }
  return `id:${message.id}`;
}

export function dedupeMessages(messages: ChatMessage[]): ChatMessage[] {
  const identities = new Set<string>();
  const result: ChatMessage[] = [];

  for (const message of messages) {
    const identity = messageIdentity(message);
    if (identities.has(identity)) {
      continue;
    }
    identities.add(identity);
    result.push(message);
  }

  return result;
}

type StringTurnMetadataKey = 'model' | 'tier' | 'reasoning';
type NumericTurnMetadataKey = 'inputTokens' | 'outputTokens' | 'totalTokens' | 'latencyMs' | 'maxContextTokens';

function patchStringMetadata(next: TurnMetadata, hint: AssistantHint, key: StringTurnMetadataKey): void {
  if (Object.prototype.hasOwnProperty.call(hint, key)) {
    next[key] = hint[key] ?? null;
  }
}

function patchNumericMetadata(next: TurnMetadata, hint: AssistantHint, key: NumericTurnMetadataKey): void {
  if (Object.prototype.hasOwnProperty.call(hint, key)) {
    next[key] = hint[key] ?? null;
  }
}

export function patchTurnMetadata(current: TurnMetadata, hint: AssistantHint): TurnMetadata {
  const next: TurnMetadata = {
    ...current,
    fileChanges: [...current.fileChanges],
  };

  patchStringMetadata(next, hint, 'model');
  patchStringMetadata(next, hint, 'tier');
  patchStringMetadata(next, hint, 'reasoning');
  patchNumericMetadata(next, hint, 'inputTokens');
  patchNumericMetadata(next, hint, 'outputTokens');
  patchNumericMetadata(next, hint, 'totalTokens');
  patchNumericMetadata(next, hint, 'latencyMs');
  patchNumericMetadata(next, hint, 'maxContextTokens');

  if (Object.prototype.hasOwnProperty.call(hint, 'fileChanges')) {
    next.fileChanges = Array.isArray(hint.fileChanges) ? hint.fileChanges : [];
  }

  return next;
}

export function mergeInitialHistory(existingMessages: ChatMessage[], historyMessages: ChatMessage[]): ChatMessage[] {
  if (existingMessages.length === 0) {
    return historyMessages;
  }

  const merged = [...historyMessages];
  const lastPersistedAssistant = [...historyMessages].reverse().find((message) => message.role === 'assistant') ?? null;
  for (const message of existingMessages) {
    if (message.persisted) {
      continue;
    }
    if (
      message.role === 'assistant'
      && message.content === lastPersistedAssistant?.content
      && message.model === lastPersistedAssistant?.model
      && message.tier === lastPersistedAssistant?.tier
      && message.skill === lastPersistedAssistant?.skill
      && message.reasoning === lastPersistedAssistant?.reasoning
    ) {
      continue;
    }
    merged.push(message);
  }
  return dedupeMessages(merged);
}

function updateAssistantMessage(
  message: ChatMessage,
  text: string,
  hint: AssistantHint | null,
  isFinal: boolean,
): ChatMessage {
  const nextModel = hint?.model ?? message.model;
  const nextTier = hint?.tier ?? message.tier;
  const nextSkill = hint?.skill ?? message.skill;
  const nextReasoning = hint?.reasoning ?? message.reasoning;

  if (!isFinal) {
    return {
      ...message,
      content: `${message.content}${text}`,
      model: nextModel,
      tier: nextTier,
      skill: nextSkill,
      reasoning: nextReasoning,
    };
  }

  if (text.length === 0 || text === message.content) {
    if (
      nextModel === message.model
      && nextTier === message.tier
      && nextSkill === message.skill
      && nextReasoning === message.reasoning
    ) {
      return message;
    }
    return {
      ...message,
      model: nextModel,
      tier: nextTier,
      skill: nextSkill,
      reasoning: nextReasoning,
    };
  }

  return {
    ...message,
    content: text.length >= message.content.length ? text : message.content,
    model: nextModel,
    tier: nextTier,
    skill: nextSkill,
    reasoning: nextReasoning,
  };
}

function createAssistantMessage(
  sessionId: string,
  messageIndex: number,
  text: string,
  hint: AssistantHint | null,
): ChatMessage {
  return {
    id: `${sessionId}:assistant:${messageIndex + 1}:${Date.now()}`,
    role: 'assistant',
    content: text,
    model: hint?.model ?? null,
    tier: hint?.tier ?? null,
    skill: hint?.skill ?? null,
    reasoning: hint?.reasoning ?? null,
    persisted: false,
  };
}

export function applyAssistantTextUpdate(
  current: ChatRuntimeSessionState,
  sessionId: string,
  text: string,
  hint: AssistantHint | null,
  isFinal: boolean,
): Pick<ChatRuntimeSessionState, 'messages' | 'running' | 'turnMetadata' | 'typing'> {
  const nextMessages = [...current.messages];
  const lastMessage = nextMessages.length > 0 ? nextMessages[nextMessages.length - 1] : null;
  const safeText = text ?? '';
  const nextTurnMetadata = hint != null ? patchTurnMetadata(current.turnMetadata, hint) : current.turnMetadata;

  if (lastMessage?.role === 'assistant' && !lastMessage.persisted) {
    nextMessages[nextMessages.length - 1] = updateAssistantMessage(lastMessage, safeText, hint, isFinal);
  } else if (safeText.length > 0) {
    nextMessages.push(createAssistantMessage(sessionId, nextMessages.length, safeText, hint));
  }

  return {
    messages: nextMessages,
    typing: false,
    running: !isFinal,
    turnMetadata: nextTurnMetadata,
  };
}
