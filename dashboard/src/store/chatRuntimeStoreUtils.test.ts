import { describe, expect, it } from 'vitest';
import type { ChatMessage, ChatRuntimeSessionState } from '../components/chat/chatRuntimeTypes';
import {
  applyAssistantTextUpdate,
  applyLiveProgressUpdate,
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
    attachments: [],
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
    }, [], false);

    expect(chunkState.messages).toHaveLength(1);
    expect(chunkState.messages[0].content).toBe('Hel');
    expect(chunkState.running).toBe(true);
    expect(chunkState.turnMetadata.reasoning).toBe('high');

    const finalState = applyAssistantTextUpdate({
      ...initialState,
      ...chunkState,
    }, 'chat-1', 'Hello', null, [], true);

    expect(finalState.messages).toHaveLength(1);
    expect(finalState.messages[0].content).toBe('Hello');
    expect(finalState.messages[0].model).toBe('openai/o3-mini');
    expect(finalState.messages[0].tier).toBe('smart');
    expect(finalState.messages[0].reasoning).toBe('high');
    expect(finalState.running).toBe(false);
    expect(finalState.typing).toBe(false);
  });

  it('creates a finalized assistant message with metadata when only assistant_done arrives', () => {
    const initialState = buildSessionState({});

    const finalState = applyAssistantTextUpdate(initialState, 'chat-1', 'Hello', {
      model: 'gemini-3.1-flash-lite-preview',
      tier: 'smart',
      reasoning: 'medium',
    }, [], true);

    expect(finalState.messages).toHaveLength(1);
    expect(finalState.messages[0].content).toBe('Hello');
    expect(finalState.messages[0].model).toBe('gemini-3.1-flash-lite-preview');
    expect(finalState.messages[0].tier).toBe('smart');
    expect(finalState.messages[0].reasoning).toBe('medium');
    expect(finalState.turnMetadata.model).toBe('gemini-3.1-flash-lite-preview');
    expect(finalState.turnMetadata.tier).toBe('smart');
    expect(finalState.turnMetadata.reasoning).toBe('medium');
    expect(finalState.running).toBe(false);
  });

  it('stores live progress updates and clears them after the final assistant message', () => {
    const initialState = buildSessionState({});
    const withProgress = applyLiveProgressUpdate(initialState, {
      type: 'summary',
      text: 'Grouped the latest shell runs into a short update.',
    });

    expect(withProgress.progress?.text).toBe('Grouped the latest shell runs into a short update.');
    expect(withProgress.running).toBe(true);

    const finalState = applyAssistantTextUpdate({
      ...initialState,
      ...withProgress,
    }, 'chat-1', 'Done', null, [], true);

    expect(finalState.progress).toBeNull();
    expect(finalState.running).toBe(false);
  });

  it('creates an assistant message when the final reply contains only attachments', () => {
    const initialState = buildSessionState({});

    const finalState = applyAssistantTextUpdate(initialState, 'chat-1', '', null, [{
      type: 'image',
      name: 'capture.png',
      mimeType: 'image/png',
      url: '/api/files/download?path=capture',
    }], true);

    expect(finalState.messages).toHaveLength(1);
    expect(finalState.messages[0].attachments).toHaveLength(1);
    expect(finalState.messages[0].content).toBe('');
    expect(finalState.running).toBe(false);
  });
});
