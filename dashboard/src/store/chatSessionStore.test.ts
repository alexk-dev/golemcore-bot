/* @vitest-environment jsdom */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { normalizeStoredOpenSessionIds, useChatSessionStore } from './chatSessionStore';
import { createUuid } from '../utils/uuid';

const OPEN_SESSIONS_KEY = 'golem-chat-open-sessions';
const ACTIVE_SESSION_KEY = 'golem-chat-session-id';

function seedStore(): string {
  const seed = createUuid();
  useChatSessionStore.setState({
    activeSessionId: seed,
    openSessionIds: [seed],
  });
  window.localStorage.setItem(ACTIVE_SESSION_KEY, seed);
  window.localStorage.setItem(OPEN_SESSIONS_KEY, JSON.stringify([seed]));
  return seed;
}

describe('chatSessionStore: multi-session state', () => {
  beforeEach(() => {
    window.localStorage.clear();
    seedStore();
  });

  afterEach(() => {
    window.localStorage.clear();
  });

  it('keeps openSessionIds non-empty and containing the active session', () => {
    const state = useChatSessionStore.getState();
    expect(state.openSessionIds.length).toBeGreaterThanOrEqual(1);
    expect(state.openSessionIds).toContain(state.activeSessionId);
  });

  it('openSession appends a new id and activates it', () => {
    const newId = createUuid();
    useChatSessionStore.getState().openSession(newId);

    const state = useChatSessionStore.getState();
    expect(state.openSessionIds).toContain(newId);
    expect(state.activeSessionId).toBe(newId);
  });

  it('openSession is idempotent when the id is already open', () => {
    const newId = createUuid();
    useChatSessionStore.getState().openSession(newId);
    const lengthAfterFirst = useChatSessionStore.getState().openSessionIds.length;

    useChatSessionStore.getState().openSession(newId);
    const lengthAfterSecond = useChatSessionStore.getState().openSessionIds.length;

    expect(lengthAfterSecond).toBe(lengthAfterFirst);
    expect(useChatSessionStore.getState().activeSessionId).toBe(newId);
  });

  it('closeSession removes the id and picks a neighbor when closing the active tab', () => {
    const first = useChatSessionStore.getState().activeSessionId;
    const second = createUuid();
    useChatSessionStore.getState().openSession(second);

    useChatSessionStore.getState().closeSession(second);

    const state = useChatSessionStore.getState();
    expect(state.openSessionIds).not.toContain(second);
    expect(state.activeSessionId).toBe(first);
  });

  it('closeSession on a non-active tab preserves the active id', () => {
    const first = useChatSessionStore.getState().activeSessionId;
    const second = createUuid();
    useChatSessionStore.getState().openSession(second);

    useChatSessionStore.getState().closeSession(first);

    const state = useChatSessionStore.getState();
    expect(state.openSessionIds).not.toContain(first);
    expect(state.activeSessionId).toBe(second);
  });

  it('closeSession with an unknown id is a no-op and leaves state intact', () => {
    const before = useChatSessionStore.getState();
    const unknown = createUuid();

    useChatSessionStore.getState().closeSession(unknown);

    const after = useChatSessionStore.getState();
    expect(after.openSessionIds).toEqual(before.openSessionIds);
    expect(after.activeSessionId).toBe(before.activeSessionId);
  });

  it('closeSession with an invalid session id is a no-op', () => {
    const before = useChatSessionStore.getState();

    useChatSessionStore.getState().closeSession('   ');

    const after = useChatSessionStore.getState();
    expect(after.openSessionIds).toEqual(before.openSessionIds);
    expect(after.activeSessionId).toBe(before.activeSessionId);
  });

  it('closeSession on the last remaining tab generates a fresh session', () => {
    const only = useChatSessionStore.getState().activeSessionId;

    useChatSessionStore.getState().closeSession(only);

    const state = useChatSessionStore.getState();
    expect(state.openSessionIds.length).toBe(1);
    expect(state.openSessionIds[0]).not.toBe(only);
    expect(state.activeSessionId).toBe(state.openSessionIds[0]);
  });

  it('startNewSession returns a new id, appends it, and activates it', () => {
    const before = useChatSessionStore.getState().activeSessionId;

    const created = useChatSessionStore.getState().startNewSession();

    const after = useChatSessionStore.getState();
    expect(created).not.toBe(before);
    expect(after.activeSessionId).toBe(created);
    expect(after.openSessionIds).toContain(created);
  });

  it('setActiveSessionId adds the id to openSessionIds when missing', () => {
    const fresh = createUuid();
    useChatSessionStore.getState().setActiveSessionId(fresh);

    const state = useChatSessionStore.getState();
    expect(state.activeSessionId).toBe(fresh);
    expect(state.openSessionIds).toContain(fresh);
  });

  it('persists openSessionIds to localStorage on mutation', () => {
    const newId = createUuid();
    useChatSessionStore.getState().openSession(newId);

    const raw = window.localStorage.getItem(OPEN_SESSIONS_KEY);
    expect(raw).not.toBeNull();
    const parsed: unknown = JSON.parse(raw ?? '[]');
    expect(Array.isArray(parsed)).toBe(true);
    expect(parsed as string[]).toContain(newId);
  });

  it('persists active session id to localStorage on switch', () => {
    const newId = createUuid();
    useChatSessionStore.getState().openSession(newId);

    expect(window.localStorage.getItem(ACTIVE_SESSION_KEY)).toBe(newId);
  });
});

describe('normalizeStoredOpenSessionIds', () => {
  it('returns the fallback wrapped in an array when storage is empty', () => {
    const fallback = createUuid();
    expect(normalizeStoredOpenSessionIds(null, fallback)).toEqual([fallback]);
    expect(normalizeStoredOpenSessionIds('', fallback)).toEqual([fallback]);
  });

  it('recovers the fallback when storage is invalid JSON', () => {
    const fallback = createUuid();
    expect(normalizeStoredOpenSessionIds('not-json', fallback)).toEqual([fallback]);
  });

  it('recovers the fallback when storage is not an array', () => {
    const fallback = createUuid();
    expect(normalizeStoredOpenSessionIds('{"foo":"bar"}', fallback)).toEqual([fallback]);
  });

  it('drops non-string and invalid entries and preserves valid ones', () => {
    const fallback = createUuid();
    const validA = createUuid();
    const validB = createUuid();
    const raw = JSON.stringify([validA, 42, '   ', '!!!invalid!!!', validB, null]);

    const result = normalizeStoredOpenSessionIds(raw, fallback);

    expect(result).toContain(validA);
    expect(result).toContain(validB);
    expect(result).toContain(fallback);
    expect(result).not.toContain('   ');
    expect(result).not.toContain('!!!invalid!!!');
  });

  it('removes duplicates while preserving first occurrence order', () => {
    const fallback = createUuid();
    const idA = createUuid();
    const idB = createUuid();
    const raw = JSON.stringify([idA, idB, idA, idB, fallback]);

    const result = normalizeStoredOpenSessionIds(raw, fallback);

    expect(result).toEqual([idA, idB, fallback]);
  });

  it('appends the fallback when it is absent from storage', () => {
    const fallback = createUuid();
    const other = createUuid();
    const raw = JSON.stringify([other]);

    const result = normalizeStoredOpenSessionIds(raw, fallback);

    expect(result).toContain(other);
    expect(result).toContain(fallback);
    expect(result[result.length - 1]).toBe(fallback);
  });
});
