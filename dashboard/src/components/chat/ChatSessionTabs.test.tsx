/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useChatSessionStore } from '../../store/chatSessionStore';
import { createUuid } from '../../utils/uuid';
import { ChatSessionTabs } from './ChatSessionTabs';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

interface TestHarness {
  container: HTMLDivElement;
  root: Root;
}

function mountHarness(): TestHarness {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(<ChatSessionTabs />);
  });
  return { container, root };
}

function unmountHarness({ container, root }: TestHarness): void {
  act(() => {
    root.unmount();
  });
  container.remove();
}

function seedSessions(count: number): string[] {
  const ids: string[] = [];
  for (let i = 0; i < count; i += 1) {
    ids.push(createUuid());
  }
  useChatSessionStore.setState({
    activeSessionId: ids[0],
    openSessionIds: ids,
  });
  window.localStorage.setItem('golem-chat-session-id', ids[0]);
  window.localStorage.setItem('golem-chat-open-sessions', JSON.stringify(ids));
  return ids;
}

function findTabButtons(container: HTMLDivElement): HTMLButtonElement[] {
  return Array.from(container.querySelectorAll<HTMLButtonElement>('[data-testid="chat-session-tab"]'));
}

function findCloseButtons(container: HTMLDivElement): HTMLButtonElement[] {
  return Array.from(container.querySelectorAll<HTMLButtonElement>('[data-testid="chat-session-close"]'));
}

function findNewChatButton(container: HTMLDivElement): HTMLButtonElement {
  const button = container.querySelector<HTMLButtonElement>('[data-testid="chat-session-new"]');
  if (button == null) {
    throw new Error('New chat button not found');
  }
  return button;
}

describe('ChatSessionTabs', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.body.innerHTML = '';
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('renders one tab per open session and marks the active one', () => {
    seedSessions(3);
    const harness = mountHarness();

    const tabs = findTabButtons(harness.container);
    expect(tabs).toHaveLength(3);
    const activeTabs = tabs.filter((tab) => tab.getAttribute('data-active') === 'true');
    expect(activeTabs).toHaveLength(1);
    expect(activeTabs[0]).toBe(tabs[0]);

    unmountHarness(harness);
  });

  it('switches the active session when another tab is clicked', () => {
    const ids = seedSessions(2);
    const harness = mountHarness();

    const tabs = findTabButtons(harness.container);
    act(() => {
      tabs[1].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    expect(useChatSessionStore.getState().activeSessionId).toBe(ids[1]);

    unmountHarness(harness);
  });

  it('closes a session via the close button without activating on parent click', () => {
    const ids = seedSessions(2);
    const harness = mountHarness();

    const closeButtons = findCloseButtons(harness.container);
    expect(closeButtons).toHaveLength(2);

    act(() => {
      closeButtons[1].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const state = useChatSessionStore.getState();
    expect(state.openSessionIds).not.toContain(ids[1]);
    expect(state.activeSessionId).toBe(ids[0]);

    unmountHarness(harness);
  });

  it('ArrowRight on a tab activates the next tab and wraps to the first', () => {
    const ids = seedSessions(3);
    const harness = mountHarness();

    const tabs = findTabButtons(harness.container);
    act(() => {
      tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    });
    expect(useChatSessionStore.getState().activeSessionId).toBe(ids[1]);

    const refreshed = findTabButtons(harness.container);
    act(() => {
      refreshed[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    });
    expect(useChatSessionStore.getState().activeSessionId).toBe(ids[2]);

    const refreshedAgain = findTabButtons(harness.container);
    act(() => {
      refreshedAgain[2].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    });
    expect(useChatSessionStore.getState().activeSessionId).toBe(ids[0]);

    unmountHarness(harness);
  });

  it('ArrowLeft on the first tab wraps to the last tab', () => {
    const ids = seedSessions(3);
    const harness = mountHarness();

    const tabs = findTabButtons(harness.container);
    act(() => {
      tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    });
    expect(useChatSessionStore.getState().activeSessionId).toBe(ids[2]);

    unmountHarness(harness);
  });

  it('Home and End jump to the first and last tab', () => {
    const ids = seedSessions(4);
    useChatSessionStore.getState().setActiveSessionId(ids[2]);
    const harness = mountHarness();

    const tabs = findTabButtons(harness.container);
    act(() => {
      tabs[2].dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    });
    expect(useChatSessionStore.getState().activeSessionId).toBe(ids[3]);

    const refreshed = findTabButtons(harness.container);
    act(() => {
      refreshed[3].dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    });
    expect(useChatSessionStore.getState().activeSessionId).toBe(ids[0]);

    unmountHarness(harness);
  });

  it('creates a fresh session when the new-chat button is clicked', () => {
    const ids = seedSessions(1);
    const harness = mountHarness();

    act(() => {
      findNewChatButton(harness.container).dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    const state = useChatSessionStore.getState();
    expect(state.openSessionIds).toHaveLength(2);
    expect(state.activeSessionId).not.toBe(ids[0]);
    expect(state.openSessionIds).toContain(ids[0]);

    unmountHarness(harness);
  });
});
