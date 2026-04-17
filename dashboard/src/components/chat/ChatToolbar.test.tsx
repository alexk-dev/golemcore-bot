/* @vitest-environment jsdom */

import { act, type ReactElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatToolbar, type ChatToolbarProps } from './ChatToolbar';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

interface TestHarness {
  container: HTMLDivElement;
  root: Root;
}

function mountHarness(element: ReactElement): TestHarness {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(element);
  });
  return { container, root };
}

function unmountHarness({ container, root }: TestHarness): void {
  act(() => {
    root.unmount();
  });
  container.remove();
}

function baseProps(): ChatToolbarProps {
  return {
    chatSessionId: 'session-12345678',
    connected: true,
    panelOpen: false,
    onNewChat: vi.fn(),
    onToggleContext: vi.fn(),
  };
}

function getRequiredElement(container: ParentNode, selector: string): Element {
  const element = container.querySelector(selector);
  expect(element).not.toBeNull();
  if (element == null) {
    throw new Error(`Element not found: ${selector}`);
  }
  return element;
}

function getRequiredButton(container: ParentNode, selector: string): HTMLButtonElement {
  const element = getRequiredElement(container, selector);
  if (!(element instanceof HTMLButtonElement)) {
    throw new Error(`Element is not a button: ${selector}`);
  }
  return element;
}

function getRequiredSelect(container: ParentNode, selector: string): HTMLSelectElement {
  const element = getRequiredElement(container, selector);
  if (!(element instanceof HTMLSelectElement)) {
    throw new Error(`Element is not a select: ${selector}`);
  }
  return element;
}

function getRequiredInput(container: ParentNode, selector: string): HTMLInputElement {
  const element = getRequiredElement(container, selector);
  if (!(element instanceof HTMLInputElement)) {
    throw new Error(`Element is not an input: ${selector}`);
  }
  return element;
}

describe('ChatToolbar', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('renders the context toggle button in standalone mode', () => {
    const harness = mountHarness(<ChatToolbar {...baseProps()} />);
    const contextBtn = harness.container.querySelector<HTMLButtonElement>(
      '[data-testid="chat-toolbar-context-toggle"]',
    );
    expect(contextBtn).not.toBeNull();
    const tierSelect = harness.container.querySelector(
      '[data-testid="chat-toolbar-tier-select"]',
    );
    expect(tierSelect).toBeNull();
    unmountHarness(harness);
  });

  it('fires onToggleContext when the context button is clicked in standalone mode', () => {
    const onToggleContext = vi.fn();
    const harness = mountHarness(
      <ChatToolbar {...baseProps()} onToggleContext={onToggleContext} />,
    );
    const btn = getRequiredButton(
      harness.container,
      '[data-testid="chat-toolbar-context-toggle"]',
    );
    act(() => {
      btn.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(onToggleContext).toHaveBeenCalledTimes(1);
    unmountHarness(harness);
  });

  it('hides the context toggle and shows a tier selector when embedded', () => {
    const harness = mountHarness(
      <ChatToolbar
        {...baseProps()}
        embedded
        tier="smart"
        tierForce={false}
        memoryPreset="general_chat"
        memoryPresetOptions={[{ id: 'general_chat', label: 'General chat' }]}
        onTierChange={vi.fn()}
        onForceChange={vi.fn()}
        onMemoryPresetChange={vi.fn()}
      />,
    );
    const contextBtn = harness.container.querySelector(
      '[data-testid="chat-toolbar-context-toggle"]',
    );
    expect(contextBtn).toBeNull();
    const tierSelect = getRequiredSelect(
      harness.container,
      '[data-testid="chat-toolbar-tier-select"]',
    );
    expect(tierSelect.value).toBe('smart');
    const memorySelect = getRequiredSelect(
      harness.container,
      '[data-testid="chat-toolbar-memory-preset-select"]',
    );
    expect(memorySelect.value).toBe('general_chat');
    unmountHarness(harness);
  });

  it('fires onTierChange when the embedded tier selector changes', () => {
    const onTierChange = vi.fn();
    const harness = mountHarness(
      <ChatToolbar
        {...baseProps()}
        embedded
        tier="balanced"
        tierForce={false}
        onTierChange={onTierChange}
        onForceChange={vi.fn()}
        onMemoryPresetChange={vi.fn()}
      />,
    );
    const select = getRequiredSelect(
      harness.container,
      '[data-testid="chat-toolbar-tier-select"]',
    );
    act(() => {
      select.value = 'deep';
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(onTierChange).toHaveBeenCalledWith('deep');
    unmountHarness(harness);
  });

  it('fires onForceChange when the embedded force toggle is flipped', () => {
    const onForceChange = vi.fn();
    const harness = mountHarness(
      <ChatToolbar
        {...baseProps()}
        embedded
        tier="balanced"
        tierForce={false}
        onTierChange={vi.fn()}
        onForceChange={onForceChange}
        onMemoryPresetChange={vi.fn()}
      />,
    );
    const force = getRequiredInput(
      harness.container,
      '[data-testid="chat-toolbar-tier-force"]',
    );
    act(() => {
      force.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(onForceChange).toHaveBeenCalledWith(true);
    unmountHarness(harness);
  });

  it('fires onMemoryPresetChange when the embedded memory selector changes', () => {
    const onMemoryPresetChange = vi.fn();
    const harness = mountHarness(
      <ChatToolbar
        {...baseProps()}
        embedded
        tier="balanced"
        tierForce={false}
        memoryPreset=""
        memoryPresetOptions={[
          { id: 'general_chat', label: 'General chat' },
          { id: 'coding_balanced', label: 'Coding balanced' },
        ]}
        onTierChange={vi.fn()}
        onForceChange={vi.fn()}
        onMemoryPresetChange={onMemoryPresetChange}
      />,
    );
    const select = getRequiredSelect(
      harness.container,
      '[data-testid="chat-toolbar-memory-preset-select"]',
    );
    act(() => {
      select.value = 'coding_balanced';
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(onMemoryPresetChange).toHaveBeenCalledWith('coding_balanced');
    unmountHarness(harness);
  });
});
