/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
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

interface MockTerminalInstance {
  open: ReturnType<typeof vi.fn>;
  onData: ReturnType<typeof vi.fn>;
  onResize: ReturnType<typeof vi.fn>;
  write: ReturnType<typeof vi.fn>;
  dispose: ReturnType<typeof vi.fn>;
  loadAddon: ReturnType<typeof vi.fn>;
  onDataHandler: ((data: string) => void) | null;
}

const terminalInstances: MockTerminalInstance[] = [];

vi.mock('@xterm/xterm', () => {
  class MockTerminal implements MockTerminalInstance {
    open = vi.fn();
    loadAddon = vi.fn();
    write = vi.fn();
    dispose = vi.fn();
    onDataHandler: ((data: string) => void) | null = null;
    onData = vi.fn((handler: (data: string) => void) => {
      this.onDataHandler = handler;
      return { dispose: vi.fn() };
    });
    onResize = vi.fn(() => ({ dispose: vi.fn() }));
    constructor() {
      terminalInstances.push(this);
    }
  }
  return { Terminal: MockTerminal };
});

vi.mock('@xterm/addon-fit', () => ({
  FitAddon: class {
    fit = vi.fn();
  },
}));

vi.mock('@xterm/addon-web-links', () => ({
  WebLinksAddon: class {
    dispose = vi.fn();
  },
}));

vi.mock('../../store/authStore', () => ({
  useAuthStore: {
    getState: () => ({ accessToken: 'test-token' }),
  },
}));

interface MockSocket {
  url: string;
  readyState: number;
  sent: string[];
  onopen: (() => void) | null;
  onmessage: ((event: { data: string }) => void) | null;
  onclose: (() => void) | null;
  onerror: ((error: unknown) => void) | null;
  send: (payload: string) => void;
  close: () => void;
}

const sockets: MockSocket[] = [];

class MockWebSocket implements MockSocket {
  readyState = 0;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: ((error: unknown) => void) | null = null;
  constructor(public url: string) {
    sockets.push(this);
  }
  send(payload: string): void {
    this.sent.push(payload);
  }
  close(): void {
    this.readyState = 3;
    this.onclose?.();
  }
}

const originalWebSocket = globalThis.WebSocket;

import { TerminalPane } from './TerminalPane';
import { useTerminalStore } from '../../store/terminalStore';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

interface Harness {
  container: HTMLDivElement;
  root: Root;
}

function mount(tabId?: string, cwd?: string): Harness {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(<TerminalPane tabId={tabId} cwd={cwd} />);
  });
  return { container, root };
}

function unmount({ container, root }: Harness): void {
  act(() => {
    root.unmount();
  });
  container.remove();
}

describe('TerminalPane', () => {
  beforeEach(() => {
    terminalInstances.length = 0;
    sockets.length = 0;
    (globalThis as unknown as { WebSocket: typeof MockWebSocket }).WebSocket = MockWebSocket;
    useTerminalStore.setState({
      tabs: [],
      activeTabId: null,
      connectionStatus: 'idle',
      pendingInput: {},
    });
  });

  afterEach(() => {
    (globalThis as unknown as { WebSocket: typeof WebSocket }).WebSocket = originalWebSocket;
    document.body.innerHTML = '';
  });

  it('mounts an xterm instance and opens a WebSocket with the JWT', () => {
    const harness = mount();
    expect(terminalInstances).toHaveLength(1);
    expect(terminalInstances[0]?.open).toHaveBeenCalled();

    expect(sockets).toHaveLength(1);
    expect(sockets[0]?.url).toContain('/ws/terminal?token=test-token');
    unmount(harness);
  });

  it('includes the working directory in the WebSocket handshake when provided', () => {
    const harness = mount(undefined, 'src/main');

    expect(sockets).toHaveLength(1);
    expect(sockets[0]?.url).toContain('/ws/terminal?token=test-token&cwd=src%2Fmain');
    unmount(harness);
  });

  it('writes decoded bytes from an output frame into the terminal', () => {
    const harness = mount();
    const terminal = terminalInstances[0];
    const socket = sockets[0];
    if (!terminal || !socket) {
      throw new Error('expected mocks to be populated');
    }
    socket.readyState = 1;
    socket.onopen?.();

    const helloBase64 = btoa('hello');
    socket.onmessage?.({ data: JSON.stringify({ type: 'output', data: helloBase64 }) });

    expect(terminal.write).toHaveBeenCalled();
    unmount(harness);
  });

  it('sends base64-encoded input frames when the terminal emits data', () => {
    const harness = mount();
    const terminal = terminalInstances[0];
    const socket = sockets[0];
    if (!terminal || !socket) {
      throw new Error('expected mocks to be populated');
    }
    socket.readyState = 1;
    socket.onopen?.();

    terminal.onDataHandler?.('ls\n');

    const inputFrame = socket.sent.find((msg) => msg.includes('"type":"input"'));
    expect(inputFrame).toBeDefined();
    unmount(harness);
  });

  it('drains pending input for its tab once the socket opens', () => {
    const store = useTerminalStore.getState();
    const tabId = store.openTab();
    store.enqueueInput(tabId, 'ls -la\n');

    const harness = mount(tabId);
    const socket = sockets[0];
    if (!socket) {
      throw new Error('expected mock socket');
    }
    socket.readyState = 1;
    socket.onopen?.();

    const inputFrame = socket.sent.find((msg) => msg.includes('"type":"input"'));
    expect(inputFrame).toBeDefined();
    expect(useTerminalStore.getState().pendingInput[tabId]).toBeUndefined();
    unmount(harness);
  });

  it('sends pending input that arrives after the socket is already open', () => {
    const store = useTerminalStore.getState();
    const tabId = store.openTab();

    const harness = mount(tabId);
    const socket = sockets[0];
    if (!socket) {
      throw new Error('expected mock socket');
    }
    socket.readyState = 1;
    socket.onopen?.();

    act(() => {
      useTerminalStore.getState().enqueueInput(tabId, 'whoami\n');
    });

    const inputFrame = socket.sent.find((msg) => msg.includes('"type":"input"'));
    expect(inputFrame).toBeDefined();
    expect(useTerminalStore.getState().pendingInput[tabId]).toBeUndefined();
    unmount(harness);
  });

  it('does not re-drain when unrelated store slices change', () => {
    const store = useTerminalStore.getState();
    const tabId = store.openTab();

    const harness = mount(tabId);
    const socket = sockets[0];
    if (!socket) {
      throw new Error('expected mock socket');
    }
    socket.readyState = 1;
    socket.onopen?.();

    act(() => {
      useTerminalStore.getState().enqueueInput(tabId, 'first\n');
    });
    const countAfterFirst = socket.sent.filter((msg) => msg.includes('"type":"input"')).length;
    expect(countAfterFirst).toBe(1);

    act(() => {
      useTerminalStore.getState().setConnectionStatus('connected');
    });
    const countAfterUnrelated = socket.sent.filter((msg) => msg.includes('"type":"input"')).length;
    expect(countAfterUnrelated).toBe(1);

    act(() => {
      const otherTabId = useTerminalStore.getState().openTab();
      useTerminalStore.getState().enqueueInput(otherTabId, 'other\n');
    });
    const countAfterOtherTab = socket.sent.filter((msg) => msg.includes('"type":"input"')).length;
    expect(countAfterOtherTab).toBe(1);

    unmount(harness);
  });

  it('produces identical input-frame shapes for onData and drained pending input', () => {
    const store = useTerminalStore.getState();
    const tabId = store.openTab();
    store.enqueueInput(tabId, 'drained\n');

    const harness = mount(tabId);
    const terminal = terminalInstances[0];
    const socket = sockets[0];
    if (!terminal || !socket) {
      throw new Error('expected mocks to be populated');
    }
    socket.readyState = 1;
    socket.onopen?.();

    terminal.onDataHandler?.('live\n');

    const inputFrames = socket.sent
      .map((raw) => JSON.parse(raw) as { type: string; data?: string })
      .filter((frame) => frame.type === 'input');
    expect(inputFrames).toHaveLength(2);
    const [firstKeys, secondKeys] = inputFrames.map((frame) => Object.keys(frame).sort());
    expect(firstKeys).toEqual(secondKeys);
    expect(firstKeys).toEqual(['data', 'type']);
    expect(inputFrames[0]?.data).toBeDefined();
    expect(inputFrames[1]?.data).toBeDefined();
    unmount(harness);
  });

  it('disposes the terminal and closes the socket on unmount', () => {
    const harness = mount();
    const terminal = terminalInstances[0];
    const socket = sockets[0];
    if (!terminal || !socket) {
      throw new Error('expected mocks to be populated');
    }

    unmount(harness);
    expect(terminal.dispose).toHaveBeenCalled();
    expect(socket.readyState).toBe(3);
  });
});
