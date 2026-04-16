/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

class TestResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

if (typeof globalThis.ResizeObserver === 'undefined') {
  (globalThis as unknown as { ResizeObserver: typeof TestResizeObserver }).ResizeObserver =
    TestResizeObserver;
}

vi.mock('./IdePage', () => ({
  default: () => <div data-testid="workspace-ide-slot">IDE</div>,
}));

interface MockChatWindowProps {
  embedded?: boolean;
}
const chatWindowPropsSpy = vi.fn<(props: MockChatWindowProps) => void>();
vi.mock('../components/chat/ChatWindow', () => ({
  default: (props: MockChatWindowProps) => {
    chatWindowPropsSpy(props);
    return <div data-testid="workspace-chat-slot">Chat</div>;
  },
}));

vi.mock('../components/terminal/TerminalPane', () => ({
  TerminalPane: ({ tabId, cwd }: { tabId?: string; cwd?: string }) => (
    <div data-testid="workspace-terminal-slot" data-tab-id={tabId} data-cwd={cwd}>
      Terminal
    </div>
  ),
}));

vi.mock('../components/terminal/TerminalTabs', () => ({
  TerminalTabs: () => <div data-testid="workspace-terminal-tabs-slot">Tabs</div>,
}));

import WorkspacePage from './WorkspacePage';
import {
  DEFAULT_WORKSPACE_LAYOUT,
  useWorkspaceLayoutStore,
} from '../store/workspaceLayoutStore';
import { useTerminalStore } from '../store/terminalStore';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

interface TestHarness {
  container: HTMLDivElement;
  root: Root;
}

function mountHarness(initialEntry = '/workspace'): TestHarness {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(
      <MemoryRouter initialEntries={[initialEntry]}>
        <WorkspacePage />
      </MemoryRouter>,
    );
  });
  return { container, root };
}

function unmountHarness({ container, root }: TestHarness): void {
  act(() => {
    root.unmount();
  });
  container.remove();
}

function resetStore(): void {
  useWorkspaceLayoutStore.setState({ ...DEFAULT_WORKSPACE_LAYOUT });
  useTerminalStore.setState({
    tabs: [],
    activeTabId: null,
    connectionStatus: 'idle',
    pendingInput: {},
  });
}

describe('WorkspacePage', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.body.innerHTML = '';
    chatWindowPropsSpy.mockClear();
    resetStore();
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('renders the IDE slot by default', () => {
    const harness = mountHarness();
    expect(harness.container.querySelector('[data-testid="workspace-ide-slot"]')).not.toBeNull();
    unmountHarness(harness);
  });

  it('renders the chat slot when chat is visible', () => {
    useWorkspaceLayoutStore.getState().setChatVisible(true);
    const harness = mountHarness();
    expect(harness.container.querySelector('[data-testid="workspace-chat-slot"]')).not.toBeNull();
    unmountHarness(harness);
  });

  it('renders ChatWindow in embedded mode so the context panel is suppressed', () => {
    useWorkspaceLayoutStore.getState().setChatVisible(true);
    const harness = mountHarness();
    expect(chatWindowPropsSpy).toHaveBeenCalled();
    const calls = chatWindowPropsSpy.mock.calls;
    const lastCall = calls[calls.length - 1];
    expect(lastCall[0]).toMatchObject({ embedded: true });
    unmountHarness(harness);
  });

  it('omits the chat slot when chat is hidden', () => {
    useWorkspaceLayoutStore.getState().setChatVisible(false);
    const harness = mountHarness();
    expect(harness.container.querySelector('[data-testid="workspace-chat-slot"]')).toBeNull();
    unmountHarness(harness);
  });

  it('omits the terminal slot when terminal is hidden (default)', () => {
    const harness = mountHarness();
    expect(harness.container.querySelector('[data-testid="workspace-terminal-slot"]')).toBeNull();
    unmountHarness(harness);
  });

  it('renders the terminal slot when terminal is visible', () => {
    useWorkspaceLayoutStore.getState().setTerminalVisible(true);
    const harness = mountHarness();
    expect(harness.container.querySelector('[data-testid="workspace-terminal-slot"]')).not.toBeNull();
    unmountHarness(harness);
  });

  it('forces chat visible when mounted with ?focus=chat', () => {
    useWorkspaceLayoutStore.getState().setChatVisible(false);
    const harness = mountHarness('/workspace?focus=chat');
    expect(useWorkspaceLayoutStore.getState().isChatVisible).toBe(true);
    expect(harness.container.querySelector('[data-testid="workspace-chat-slot"]')).not.toBeNull();
    unmountHarness(harness);
  });

  it('leaves chat hidden when mounted with ?focus=editor', () => {
    useWorkspaceLayoutStore.getState().setChatVisible(false);
    const harness = mountHarness('/workspace?focus=editor');
    expect(useWorkspaceLayoutStore.getState().isChatVisible).toBe(false);
    unmountHarness(harness);
  });

  it('renders the toolbar as an ARIA toolbar landmark', () => {
    const harness = mountHarness();
    const toolbar = harness.container.querySelector('[role="toolbar"]');
    expect(toolbar).not.toBeNull();
    expect(toolbar?.getAttribute('aria-label')).toBe('Workspace layout');
    unmountHarness(harness);
  });

  it('renders TerminalTabs alongside the terminal pane when terminal is visible', () => {
    useWorkspaceLayoutStore.getState().setTerminalVisible(true);
    const harness = mountHarness();
    expect(
      harness.container.querySelector('[data-testid="workspace-terminal-tabs-slot"]'),
    ).not.toBeNull();
    unmountHarness(harness);
  });

  it('keeps inactive terminal panes mounted so tab switching does not close PTY sessions', () => {
    const firstTabId = useTerminalStore.getState().openTab('src/main');
    const secondTabId = useTerminalStore.getState().openTab('dashboard');
    useWorkspaceLayoutStore.getState().setTerminalVisible(true);

    const harness = mountHarness();

    const terminalSlots = harness.container.querySelectorAll('[data-testid="workspace-terminal-slot"]');
    expect(terminalSlots).toHaveLength(2);
    expect(
      harness.container.querySelector(`[data-testid="workspace-terminal-session-${firstTabId}"]`)
        ?.hasAttribute('hidden'),
    ).toBe(true);
    expect(
      harness.container.querySelector(`[data-testid="workspace-terminal-session-${secondTabId}"]`)
        ?.hasAttribute('hidden'),
    ).toBe(false);

    act(() => {
      useTerminalStore.getState().setActiveTab(firstTabId);
    });

    expect(
      harness.container.querySelector(`[data-testid="workspace-terminal-session-${firstTabId}"]`)
        ?.hasAttribute('hidden'),
    ).toBe(false);
    expect(
      harness.container.querySelector(`[data-testid="workspace-terminal-session-${secondTabId}"]`)
        ?.hasAttribute('hidden'),
    ).toBe(true);

    unmountHarness(harness);
  });

  it('toggles terminal visibility when Ctrl+` is pressed', () => {
    const harness = mountHarness();
    expect(useWorkspaceLayoutStore.getState().isTerminalVisible).toBe(false);

    act(() => {
      window.dispatchEvent(
        new KeyboardEvent('keydown', { key: '`', ctrlKey: true, bubbles: true }),
      );
    });
    expect(useWorkspaceLayoutStore.getState().isTerminalVisible).toBe(true);

    act(() => {
      window.dispatchEvent(
        new KeyboardEvent('keydown', { key: '`', ctrlKey: true, bubbles: true }),
      );
    });
    expect(useWorkspaceLayoutStore.getState().isTerminalVisible).toBe(false);

    unmountHarness(harness);
  });

  it('exposes toggle controls for chat and terminal panels', () => {
    const harness = mountHarness();

    const chatToggle = harness.container.querySelector<HTMLButtonElement>(
      '[data-testid="workspace-toggle-chat"]',
    );
    const terminalToggle = harness.container.querySelector<HTMLButtonElement>(
      '[data-testid="workspace-toggle-terminal"]',
    );

    expect(chatToggle).not.toBeNull();
    expect(terminalToggle).not.toBeNull();

    act(() => {
      chatToggle?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(useWorkspaceLayoutStore.getState().isChatVisible).toBe(false);

    act(() => {
      terminalToggle?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(useWorkspaceLayoutStore.getState().isTerminalVisible).toBe(true);

    unmountHarness(harness);
  });
});
