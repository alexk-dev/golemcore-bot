import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { OutboundChatPayload } from '../components/chat/chatInputTypes';
import type { ChatMessage } from '../components/chat/chatRuntimeTypes';
import { useChatRuntimeStore } from './chatRuntimeStore';

function createOutboundPayload(): OutboundChatPayload {
  return {
    text: 'hello',
    attachments: [],
  };
}

function createOptimisticMessage(overrides: Partial<ChatMessage>): ChatMessage {
  return {
    id: 'client-1',
    role: 'user',
    content: 'hello',
    model: null,
    tier: null,
    reasoning: null,
    clientStatus: 'pending',
    outbound: createOutboundPayload(),
    clientMessageId: 'client-1',
    persisted: false,
    ...overrides,
  };
}

describe('chatRuntimeStore', () => {
  beforeEach(() => {
    useChatRuntimeStore.getState().resetAll();
  });

  it('marks optimistic messages as failed when there is no active transport', () => {
    useChatRuntimeStore.getState().appendOptimisticUserMessage('chat-1', createOptimisticMessage({}));

    const sent = useChatRuntimeStore.getState().sendMessage(
      'chat-1',
      'client-instance',
      'client-1',
      createOutboundPayload(),
    );

    expect(sent).toBe(false);
    expect(useChatRuntimeStore.getState().sessions['chat-1'].messages[0].clientStatus).toBe('failed');
    expect(useChatRuntimeStore.getState().sessions['chat-1'].running).toBe(false);
  });

  it('routes outbound payloads through the registered transport and keeps the session running', () => {
    const sendMessage = vi.fn(() => true);
    useChatRuntimeStore.getState().registerTransport({
      sendBind: () => true,
      sendMessage,
      stop: () => true,
    });
    useChatRuntimeStore.getState().appendOptimisticUserMessage('chat-1', createOptimisticMessage({}));

    const sent = useChatRuntimeStore.getState().sendMessage(
      'chat-1',
      'client-instance',
      'client-1',
      createOutboundPayload(),
    );

    expect(sent).toBe(true);
    expect(sendMessage).toHaveBeenCalledWith({
      text: 'hello',
      attachments: [],
      sessionId: 'chat-1',
      clientInstanceId: 'client-instance',
      clientMessageId: 'client-1',
    });
    expect(useChatRuntimeStore.getState().sessions['chat-1'].running).toBe(true);
  });

  it('keeps live optimistic messages when persisted history is hydrated later', () => {
    useChatRuntimeStore.getState().appendOptimisticUserMessage('chat-1', createOptimisticMessage({}));

    useChatRuntimeStore.getState().hydrateHistory({
      sessionId: 'chat-1',
      sessionRecordId: 'web:chat-1',
      messages: [{
        id: 'persisted-assistant-1',
        role: 'assistant',
        content: 'Persisted answer',
        model: 'openai/o3-mini',
        tier: 'smart',
        reasoning: 'high',
        persisted: true,
      }],
      hasMoreHistory: false,
      oldestLoadedMessageId: 'persisted-assistant-1',
    });

    const messages = useChatRuntimeStore.getState().sessions['chat-1'].messages;
    expect(messages).toHaveLength(2);
    expect(messages[0].id).toBe('persisted-assistant-1');
    expect(messages[1].id).toBe('client-1');
  });
});
