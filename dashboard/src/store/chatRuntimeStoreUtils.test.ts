import { describe, expect, it } from 'vitest';
import type { ChatMessage, ChatRuntimeSessionState } from '../components/chat/chatRuntimeTypes';
import {
  applyAssistantTextUpdate,
  createEmptySessionState,
  mergeInitialHistory,
} from './chatRuntimeStoreUtils';

function createUserMessage(overrides: Partial<ChatMessage>): ChatMessage {
  return {
    id: 'message-1',
    role: 'user',
    content: 'hello',
    model: null,
    tier: null,
    reasoning: null,
    persisted: false,
    ...overrides,
  };
}

function buildSessionState(overrides: Partial<ChatRuntimeSessionState>): ChatRuntimeSessionState {
  return {
    ...createEmptySessionState(),
    ...overrides,
  };
}

describe('chatRuntimeStoreUtils', () => {
  it('dedupes optimistic user messages when persisted history arrives with the same client message id', () => {
    const optimisticUser = createUserMessage({
      id: 'optimistic-1',
      clientMessageId: 'client-1',
      persisted: false,
    });
    const persistedUser = createUserMessage({
      id: 'persisted-1',
      clientMessageId: 'client-1',
      persisted: true,
    });

    expect(mergeInitialHistory([optimisticUser], [persistedUser])).toEqual([persistedUser]);
  });

  it('accumulates assistant chunks into a single live assistant message and finalizes the text', () => {
    const initialState = buildSessionState({});
    const chunkState = applyAssistantTextUpdate(initialState, 'chat-1', 'Hel', {
      model: 'openai/o3-mini',
      reasoning: 'high',
      tier: 'smart',
    }, false);

    expect(chunkState.messages).toHaveLength(1);
    expect(chunkState.messages[0].content).toBe('Hel');
    expect(chunkState.running).toBe(true);
    expect(chunkState.turnMetadata.reasoning).toBe('high');

    const finalState = applyAssistantTextUpdate({
      ...initialState,
      ...chunkState,
    }, 'chat-1', 'Hello', null, true);

    expect(finalState.messages).toHaveLength(1);
    expect(finalState.messages[0].content).toBe('Hello');
    expect(finalState.running).toBe(false);
    expect(finalState.typing).toBe(false);
  });
});
