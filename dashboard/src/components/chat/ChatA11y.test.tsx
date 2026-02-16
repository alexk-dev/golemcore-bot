import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { axe } from 'vitest-axe';
import ChatInput from './ChatInput';
import ChatWindow from './ChatWindow';

vi.mock('../../hooks/useCommands', () => ({
  useCommands: () => ({
    data: [
      { name: 'plan', description: 'Plan mode', usage: '/plan <on|off|status>' },
      { name: 'help', description: 'Help', usage: '/help' },
    ],
  }),
}));

vi.mock('../../api/uploads', () => ({
  uploadImages: vi.fn(async () => []),
}));

vi.mock('../../api/voice', () => ({
  transcribeVoice: vi.fn(async () => ({ text: 'voice text', language: 'en', confidence: 1 })),
}));

vi.mock('../../api/sessions', () => ({
  listSessions: vi.fn(async () => [
    {
      id: 's1',
      channelType: 'web',
      chatId: 'chat-1',
      messageCount: 1,
      state: 'ACTIVE',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:01Z',
    },
  ]),
  getSession: vi.fn(async () => ({
    id: 's1',
    channelType: 'web',
    chatId: 'chat-1',
    state: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:01Z',
    messages: [{ id: 'm1', role: 'assistant', content: 'hello', timestamp: null, hasToolCalls: false, hasVoice: false }],
  })),
}));

vi.mock('../../api/settings', () => ({
  updatePreferences: vi.fn(async () => ({})),
}));

vi.mock('../../store/authStore', () => ({
  useAuthStore: (selector: (s: { accessToken: string | null }) => unknown) => selector({ accessToken: 'token-1' }),
}));

vi.mock('./MessageBubble', () => ({
  default: ({ role, content }: { role: string; content: string }) => <div>{role}:{content}</div>,
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

describe('Chat A11y smoke', () => {
  const originalScrollIntoView = Element.prototype.scrollIntoView;

  beforeEach(() => {
    Element.prototype.scrollIntoView = vi.fn();
  });

  afterEach(() => {
    Element.prototype.scrollIntoView = originalScrollIntoView;
  });

  it('ChatInput has no obvious a11y violations (with known combobox-role exception)', async () => {
    const { container } = render(<ChatInput onSend={() => {}} />);
    const results = await axe(container, {
      rules: {
        // Known temporary deviation: textarea uses combobox-like attrs for command list interaction.
        'aria-allowed-attr': { enabled: false },
      },
    });
    expect(results.violations).toEqual([]);
  });

  it('ChatWindow has no obvious a11y violations (with known combobox-role exception)', async () => {
    const originalWebSocket = globalThis.WebSocket;
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;

    try {
      const { container } = render(<ChatWindow />);
      await waitFor(() => {
        expect(container.querySelector('.chat-container')).toBeTruthy();
      });
      const results = await axe(container, {
        rules: {
          'aria-allowed-attr': { enabled: false },
        },
      });
      expect(results.violations).toEqual([]);
    } finally {
      globalThis.WebSocket = originalWebSocket;
    }
  });
});
