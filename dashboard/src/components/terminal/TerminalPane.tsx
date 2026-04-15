import { useEffect, useRef, type ReactElement } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { WebLinksAddon } from '@xterm/addon-web-links';
import '@xterm/xterm/css/xterm.css';
import { useAuthStore } from '../../store/authStore';
import { useTerminalStore } from '../../store/terminalStore';

interface TerminalPaneProps {
  tabId?: string;
}

interface OutputFrame {
  type: 'output';
  data: string;
}

interface ExitFrame {
  type: 'exit';
  code: number;
}

interface ErrorFrame {
  type: 'error';
  message: string;
}

type InboundFrame = OutputFrame | ExitFrame | ErrorFrame;

function decodeBase64ToBytes(encoded: string): Uint8Array {
  const binary = atob(encoded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function encodeBytesToBase64(chunk: string): string {
  const utf8 = new TextEncoder().encode(chunk);
  let binary = '';
  for (let index = 0; index < utf8.length; index += 1) {
    binary += String.fromCharCode(utf8[index]);
  }
  return btoa(binary);
}

function buildTerminalUrl(token: string | null): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const host = window.location.host;
  const query = token ? `?token=${encodeURIComponent(token)}` : '';
  return `${protocol}//${host}/ws/terminal${query}`;
}

function parseInbound(raw: string): InboundFrame | null {
  try {
    const parsed = JSON.parse(raw) as InboundFrame;
    return parsed;
  } catch {
    return null;
  }
}

export function TerminalPane({ tabId }: TerminalPaneProps = {}): ReactElement {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const socketRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    // Mount xterm.js and bridge it to the backend WebSocket for the session lifetime.
    const container = containerRef.current;
    if (!container) {
      return undefined;
    }

    const terminal = new Terminal({
      convertEol: true,
      cursorBlink: true,
      fontFamily: 'ui-monospace, "JetBrains Mono", Menlo, Consolas, monospace',
      fontSize: 13,
    });
    const fitAddon = new FitAddon();
    terminal.loadAddon(fitAddon);
    terminal.loadAddon(new WebLinksAddon());
    terminal.open(container);

    const token = useAuthStore.getState().accessToken;
    const socket = new WebSocket(buildTerminalUrl(token));
    socketRef.current = socket;

    const sendInputChunk = (chunk: string): void => {
      if (socket.readyState !== 1) {
        return;
      }
      socket.send(
        JSON.stringify({ type: 'input', data: encodeBytesToBase64(chunk) }),
      );
    };

    const drainPendingForTab = (): void => {
      if (tabId == null) {
        return;
      }
      const drained = useTerminalStore.getState().consumePendingInput(tabId);
      for (const chunk of drained) {
        sendInputChunk(chunk);
      }
    };

    const inputSubscription = terminal.onData((chunk: string) => {
      sendInputChunk(chunk);
    });

    const resizeSubscription = terminal.onResize(({ cols, rows }) => {
      if (socket.readyState !== 1) {
        return;
      }
      socket.send(JSON.stringify({ type: 'resize', cols, rows }));
    });

    socket.onopen = (): void => {
      try {
        fitAddon.fit();
      } catch (error) {
        console.error('[terminal] initial fit failed', error);
      }
      drainPendingForTab();
    };

    const unsubscribePending = tabId == null
      ? (): void => {}
      : useTerminalStore.subscribe(
          (state) => state.pendingInput[tabId],
          (current, previous) => {
            if (current === previous || current == null || current.length === 0) {
              return;
            }
            if (socket.readyState !== 1) {
              return;
            }
            drainPendingForTab();
          },
        );

    socket.onmessage = (event: MessageEvent<string>): void => {
      const frame = parseInbound(event.data);
      if (!frame) {
        return;
      }
      if (frame.type === 'output') {
        terminal.write(decodeBase64ToBytes(frame.data));
        return;
      }
      if (frame.type === 'exit') {
        terminal.write(`\r\n[process exited with code ${frame.code}]\r\n`);
        return;
      }
      if (frame.type === 'error') {
        terminal.write(`\r\n[terminal error: ${frame.message}]\r\n`);
      }
    };

    socket.onerror = (event): void => {
      console.error('[terminal] socket error', event);
    };

    return (): void => {
      unsubscribePending();
      inputSubscription.dispose();
      resizeSubscription.dispose();
      if (socket.readyState === 0 || socket.readyState === 1) {
        socket.close();
      }
      terminal.dispose();
      socketRef.current = null;
    };
  }, [tabId]);

  return <div ref={containerRef} className="terminal-pane" />;
}
