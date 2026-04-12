import { describe, expect, it } from 'vitest';
import type { ChatRuntimeSessionState } from './chatRuntimeTypes';
import { shouldLoadPersistedHistory } from './useChatSessionHistory';

function createSessionState(overrides: Partial<ChatRuntimeSessionState>): ChatRuntimeSessionState {
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
    ...overrides,
  };
}

describe('useChatSessionHistory', () => {
  it('skips persisted history fetches for invalid session ids and fully resolved sessions', () => {
    const invalidSession = createSessionState({});
    const resolvedSession = createSessionState({
      historyLoaded: true,
      sessionRecordId: 'web:chat-1',
    });

    expect(shouldLoadPersistedHistory(
      'bad:key',
      {
        historyLoaded: invalidSession.historyLoaded,
        sessionRecordId: invalidSession.sessionRecordId,
        messageCount: invalidSession.messages.length,
        running: invalidSession.running,
        typing: invalidSession.typing,
      },
      0,
    )).toBe(false);
    expect(shouldLoadPersistedHistory(
      'chat-1',
      {
        historyLoaded: resolvedSession.historyLoaded,
        sessionRecordId: resolvedSession.sessionRecordId,
        messageCount: resolvedSession.messages.length,
        running: resolvedSession.running,
        typing: resolvedSession.typing,
      },
      0,
    )).toBe(false);
  });

  it('loads history for new sessions and explicit reloads', () => {
    const unloadedSession = createSessionState({ historyLoaded: false });
    const resolvedSession = createSessionState({
      historyLoaded: true,
      sessionRecordId: 'web:chat-1',
    });

    expect(shouldLoadPersistedHistory(
      'chat-1',
      {
        historyLoaded: unloadedSession.historyLoaded,
        sessionRecordId: unloadedSession.sessionRecordId,
        messageCount: unloadedSession.messages.length,
        running: unloadedSession.running,
        typing: unloadedSession.typing,
      },
      0,
    )).toBe(true);
    expect(shouldLoadPersistedHistory(
      'chat-1',
      {
        historyLoaded: resolvedSession.historyLoaded,
        sessionRecordId: resolvedSession.sessionRecordId,
        messageCount: resolvedSession.messages.length,
        running: resolvedSession.running,
        typing: resolvedSession.typing,
      },
      1,
    )).toBe(true);
  });

  it('retries unresolved sessions only after local activity appears', () => {
    const idleSession = createSessionState({
      historyLoaded: true,
      sessionRecordId: null,
      messages: [],
      running: false,
      typing: false,
    });
    const activeSession = createSessionState({
      historyLoaded: true,
      sessionRecordId: null,
      messages: [{
        id: 'client-1',
        role: 'user',
        content: 'hello',
        model: null,
        tier: null,
        skill: null,
        reasoning: null,
        attachments: [],
        clientMessageId: 'client-1',
        persisted: false,
      }],
    });

    expect(shouldLoadPersistedHistory(
      'chat-1',
      {
        historyLoaded: idleSession.historyLoaded,
        sessionRecordId: idleSession.sessionRecordId,
        messageCount: idleSession.messages.length,
        running: idleSession.running,
        typing: idleSession.typing,
      },
      0,
    )).toBe(false);
    expect(shouldLoadPersistedHistory(
      'chat-1',
      {
        historyLoaded: activeSession.historyLoaded,
        sessionRecordId: activeSession.sessionRecordId,
        messageCount: activeSession.messages.length,
        running: activeSession.running,
        typing: activeSession.typing,
      },
      0,
    )).toBe(true);
  });
});
