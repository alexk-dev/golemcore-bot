import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom/server';
import { describe, expect, it, vi } from 'vitest';

import SessionsPage from './SessionsPage';

const sessionHookMocks = vi.hoisted(() => ({
  useSessions: vi.fn(),
  useDeleteSession: vi.fn(),
  useCompactSession: vi.fn(),
  useClearSession: vi.fn(),
}));

vi.mock('../hooks/useSessions', () => ({
  useSessions: sessionHookMocks.useSessions,
  useDeleteSession: sessionHookMocks.useDeleteSession,
  useCompactSession: sessionHookMocks.useCompactSession,
  useClearSession: sessionHookMocks.useClearSession,
}));

vi.mock('react-hot-toast', () => ({
  default: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

vi.mock('../components/common/ConfirmModal', () => ({
  default: () => null,
}));

describe('SessionsPage', () => {
  it('renders maintenance note about automatic cleanup configuration', () => {
    sessionHookMocks.useSessions.mockReturnValue({
      data: [],
      isLoading: false,
    });
    sessionHookMocks.useDeleteSession.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
    sessionHookMocks.useCompactSession.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
    sessionHookMocks.useClearSession.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });

    const html = renderToStaticMarkup(
      <StaticRouter location="/sessions">
        <SessionsPage />
      </StaticRouter>,
    );

    expect(html).toContain('Automatic cleanup is configured in Settings');
    expect(html).toContain('/settings/usage');
  });

  it('renders session id links to full-page detail routes', () => {
    sessionHookMocks.useSessions.mockReturnValue({
      data: [
        {
          id: 'session-123456789',
          channelType: 'web',
          chatId: 'chat-1',
          conversationKey: 'conv-1',
          transportChatId: null,
          messageCount: 5,
          state: 'ACTIVE',
          createdAt: null,
          updatedAt: '2026-03-22T10:00:00Z',
          title: 'Trace session',
          preview: 'Analyze trace',
          active: false,
        },
      ],
      isLoading: false,
    });
    sessionHookMocks.useDeleteSession.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
    sessionHookMocks.useCompactSession.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
    sessionHookMocks.useClearSession.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });

    const html = renderToStaticMarkup(
      <StaticRouter location="/sessions">
        <SessionsPage />
      </StaticRouter>,
    );

    expect(html).toContain('/sessions/session-123456789/messages');
    expect(html).not.toContain('Session: session-123456789');
  });
});
