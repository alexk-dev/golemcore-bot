import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ChatWindow from './ChatWindow';

const listSessionsMock = vi.fn();
const getSessionMock = vi.fn();
const updatePreferencesMock = vi.fn();

vi.mock('../../api/sessions', () => ({
  listSessions: (...args: unknown[]) => listSessionsMock(...args),
  getSession: (...args: unknown[]) => getSessionMock(...args),
}));

vi.mock('../../api/settings', () => ({
  updatePreferences: (...args: unknown[]) => updatePreferencesMock(...args),
}));

vi.mock('../../store/authStore', () => ({
  useAuthStore: (selector: (s: { accessToken: string | null }) => unknown) => selector({ accessToken: 'token-1' }),
}));

vi.mock('./MessageBubble', () => ({
  default: ({ role, content }: { role: string; content: string }) => (
    <div data-testid="message-bubble">{role}:{content}</div>
  ),
}));

vi.mock('./ChatInput', () => ({
  default: ({ onSend, disabled }: { onSend: (t: string) => void; disabled?: boolean }) => (
    <button type="button" onClick={() => onSend('hello from test')} disabled={disabled}>
      Send test message
    </button>
  ),
}));

class MockWebSocket {
  static OPEN = 1;
  readyState = 1;
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  send = vi.fn();
  close = vi.fn();

  constructor(_url: string) {
    setTimeout(() => this.onopen?.(), 0);
  }
}

describe('ChatWindow', () => {
  const originalWebSocket = globalThis.WebSocket;
  const originalScrollIntoView = Element.prototype.scrollIntoView;
  const scrollIntoViewMock = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    Element.prototype.scrollIntoView = scrollIntoViewMock;

    listSessionsMock.mockResolvedValue([
      {
        id: 's1',
        channelType: 'web',
        chatId: 'chat-1',
        messageCount: 120,
        state: 'ACTIVE',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:01Z',
      },
    ]);

    const messages = Array.from({ length: 120 }).map((_, i) => ({
      id: `m${i}`,
      role: i % 2 === 0 ? 'user' : 'assistant',
      content: `msg-${i}`,
      timestamp: null,
      hasToolCalls: false,
      hasVoice: false,
    }));

    getSessionMock.mockResolvedValue({
      id: 's1',
      channelType: 'web',
      chatId: 'chat-1',
      state: 'ACTIVE',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:01Z',
      messages,
    });
  });

  afterEach(() => {
    globalThis.WebSocket = originalWebSocket;
    Element.prototype.scrollIntoView = originalScrollIntoView;
  });

  it('renders last window of history and can load earlier messages', async () => {
    render(<ChatWindow />);

    await waitFor(() => {
      expect(screen.getAllByTestId('message-bubble').length).toBe(50);
    });

    const loadMore = screen.getByRole('button', { name: /Load earlier messages/i });
    fireEvent.click(loadMore);

    await waitFor(() => {
      expect(screen.getAllByTestId('message-bubble').length).toBe(100);
    });
  });

  it('uses smooth scroll behavior when user sends a message', async () => {
    render(<ChatWindow />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Send test message/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Send test message/i }));

    await waitFor(() => {
      expect(scrollIntoViewMock).toHaveBeenCalledWith({ behavior: 'smooth' });
    });
  });
});
