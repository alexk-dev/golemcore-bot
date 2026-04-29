import type { ChatMessage } from '../chat/chatRuntimeTypes';
import type {
  AgentThreadItem,
  AssistantMessageViewModel,
  ToolCallStatus,
  ToolCallViewModel,
  UserMessageViewModel,
} from './types';

/**
 * Convert chat history into a thread of harness items. The current backend
 * only emits user / assistant messages, optionally embedding `[Tool: name]`
 * markers. This adapter shields UI components from those raw shapes.
 */

interface ParsedToolMarker {
  name: string;
  target: string;
  status: ToolCallStatus;
}

const TOOL_PATTERN = /\[Tool:\s*([^\]]+)]\s*(\[Result:\s*([^\]]+)])?/g;

function parseToolMarkers(content: string): ParsedToolMarker[] {
  const out: ParsedToolMarker[] = [];
  let match: RegExpExecArray | null = TOOL_PATTERN.exec(content);
  while (match !== null) {
    const fullName = match[1].trim();
    const result = match[3]?.trim();
    const [name, ...rest] = fullName.split(/\s+/);
    out.push({
      name,
      target: rest.join(' '),
      status: result == null ? 'pending' : result.toLowerCase().includes('error') ? 'failed' : 'success',
    });
    match = TOOL_PATTERN.exec(content);
  }
  return out;
}

function buildToolCallsFromMarkers(messageId: string, runId: string, content: string): ToolCallViewModel[] {
  return parseToolMarkers(content).map((marker, index) => ({
    id: `${messageId}::tool::${index}`,
    runId,
    toolName: marker.name,
    displayTarget: marker.target.length > 0 ? marker.target : undefined,
    status: marker.status,
  }));
}

function toUserMessage(message: ChatMessage): UserMessageViewModel {
  return {
    id: message.id,
    text: message.content,
    createdAt: new Date().toISOString(),
  };
}

function toAssistantMessage(message: ChatMessage): AssistantMessageViewModel {
  return {
    id: message.id,
    text: message.content,
    createdAt: new Date().toISOString(),
    modelLabel: message.model ?? undefined,
    tierLabel: message.tier ?? undefined,
  };
}

export function normalizeChatMessages(messages: ChatMessage[], runId: string): AgentThreadItem[] {
  const items: AgentThreadItem[] = [];
  messages.forEach((message) => {
    if (message.role === 'user') {
      items.push({ type: 'user_message', message: toUserMessage(message) });
      return;
    }
    items.push({ type: 'assistant_message', message: toAssistantMessage(message) });
    const calls = buildToolCallsFromMarkers(message.id, runId, message.content);
    if (calls.length > 0) {
      items.push({ type: 'tool_calls', calls });
    }
  });
  return items;
}
