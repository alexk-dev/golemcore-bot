/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useChatSessionStore } from '../../store/chatSessionStore';
import { createUuid } from '../../utils/uuid';
import { useChatSessionHotkeys } from './useChatSessionHotkeys';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

interface HotkeyHarness {
  container: HTMLDivElement;
  root: Root;
}

function Harness(): null {
  useChatSessionHotkeys();
  return null;
}

function mount(): HotkeyHarness {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(<Harness />);
  });
  return { container, root };
}

function unmount({ container, root }: HotkeyHarness): void {
  act(() => {
    root.unmount();
  });
  container.remove();
}

function seed(count: number): string[] {
  const ids: string[] = [];
  for (let i = 0; i < count; i += 1) {
    ids.push(createUuid());
  }
  useChatSessionStore.setState({
    activeSessionId: ids[0],
    openSessionIds: ids,
  });
  return ids;
}

function pressKey(key: string, altKey: boolean): void {
  const event = new KeyboardEvent('keydown', { key, altKey, bubbles: true });
  act(() => {
    window.dispatchEvent(event);
  });
}

describe('useChatSessionHotkeys', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('Alt+N creates and activates a new session', () => {
    const [first] = seed(1);
    const harness = mount();

    pressKey('n', true);

    const state = useChatSessionStore.getState();
    expect(state.openSessionIds).toHaveLength(2);
    expect(state.activeSessionId).not.toBe(first);

    unmount(harness);
  });

  it('Alt+W closes the active session', () => {
    const [first, second] = seed(2);
    useChatSessionStore.getState().setActiveSessionId(second);
    const harness = mount();

    pressKey('w', true);

    const state = useChatSessionStore.getState();
    expect(state.openSessionIds).not.toContain(second);
    expect(state.activeSessionId).toBe(first);

    unmount(harness);
  });

  it('Alt+<digit> switches to the Nth session', () => {
    const ids = seed(3);
    const harness = mount();

    pressKey('3', true);

    expect(useChatSessionStore.getState().activeSessionId).toBe(ids[2]);

    unmount(harness);
  });

  it('Alt+<digit> beyond range is a no-op', () => {
    const ids = seed(2);
    const harness = mount();

    pressKey('5', true);

    expect(useChatSessionStore.getState().activeSessionId).toBe(ids[0]);

    unmount(harness);
  });

  it('unbinds the listener on unmount', () => {
    const [first] = seed(1);
    const harness = mount();
    unmount(harness);

    pressKey('n', true);

    const state = useChatSessionStore.getState();
    expect(state.openSessionIds).toHaveLength(1);
    expect(state.activeSessionId).toBe(first);
  });

  it('ignores keypresses without the Alt modifier', () => {
    const [first] = seed(1);
    const harness = mount();

    pressKey('n', false);

    const state = useChatSessionStore.getState();
    expect(state.openSessionIds).toHaveLength(1);
    expect(state.activeSessionId).toBe(first);

    unmount(harness);
  });

  it('ignores Alt+N when focus is inside a text input', () => {
    const [first] = seed(1);
    const harness = mount();

    const input = document.createElement('input');
    input.type = 'text';
    document.body.appendChild(input);
    input.focus();

    const event = new KeyboardEvent('keydown', { key: 'n', altKey: true, bubbles: true });
    act(() => {
      input.dispatchEvent(event);
    });

    const state = useChatSessionStore.getState();
    expect(state.openSessionIds).toHaveLength(1);
    expect(state.activeSessionId).toBe(first);

    input.remove();
    unmount(harness);
  });

  it('ignores Alt+W when focus is inside a textarea', () => {
    const [first, second] = seed(2);
    useChatSessionStore.getState().setActiveSessionId(second);
    const harness = mount();

    const textarea = document.createElement('textarea');
    document.body.appendChild(textarea);
    textarea.focus();

    const event = new KeyboardEvent('keydown', { key: 'w', altKey: true, bubbles: true });
    act(() => {
      textarea.dispatchEvent(event);
    });

    const state = useChatSessionStore.getState();
    expect(state.openSessionIds).toContain(second);
    expect(state.activeSessionId).toBe(second);
    expect(state.openSessionIds).toContain(first);

    textarea.remove();
    unmount(harness);
  });

  it('ignores Alt+<digit> when focus is inside a contenteditable element', () => {
    const ids = seed(3);
    const harness = mount();

    const editable = document.createElement('div');
    editable.setAttribute('contenteditable', 'true');
    document.body.appendChild(editable);
    editable.focus();

    const event = new KeyboardEvent('keydown', { key: '3', altKey: true, bubbles: true });
    act(() => {
      editable.dispatchEvent(event);
    });

    expect(useChatSessionStore.getState().activeSessionId).toBe(ids[0]);

    editable.remove();
    unmount(harness);
  });
});
