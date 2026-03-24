import { renderToStaticMarkup } from 'react-dom/server';
import { Route, Routes } from 'react-router-dom';
import { StaticRouter } from 'react-router-dom/server';
import { describe, expect, it, vi } from 'vitest';

import SessionDetailsPage from './SessionDetailsPage';

const sessionHookMocks = vi.hoisted(() => ({
  useSession: vi.fn(),
}));

vi.mock('../hooks/useSessions', () => ({
  useSession: sessionHookMocks.useSession,
}));

vi.mock('../components/sessions/SessionTraceTab', () => ({
  SessionTraceTab: ({ sessionId }: { sessionId: string | null }) => <div>Trace tab for {sessionId}</div>,
}));

describe('SessionDetailsPage', () => {
  it('renders session header and messages tab for URL-driven messages route', () => {
    sessionHookMocks.useSession.mockReturnValue({
      data: {
        id: 'session-1',
        channelType: 'web',
        chatId: 'chat-1',
        conversationKey: 'conv-1',
        transportChatId: null,
        state: 'ACTIVE',
        createdAt: null,
        updatedAt: '2026-03-22T10:00:00Z',
        messages: [
          {
            id: 'message-1',
            role: 'user',
            content: 'Analyze trace',
            timestamp: '2026-03-22T10:00:01Z',
            hasToolCalls: false,
            hasVoice: false,
            model: null,
            modelTier: null,
            skill: null,
            reasoning: null,
            clientMessageId: null,
            attachments: [],
          },
        ],
      },
      isLoading: false,
      isError: false,
      error: null,
    });

    const html = renderToStaticMarkup(
      <StaticRouter location="/sessions/session-1/messages">
        <Routes>
          <Route path="/sessions/:sessionId/:tab" element={<SessionDetailsPage />} />
        </Routes>
      </StaticRouter>,
    );

    expect(html).toContain('Session session-1');
    expect(html).toContain('Analyze trace');
    expect(html).toContain('/sessions/session-1/trace');
  });

  it('renders trace tab content for URL-driven trace route', () => {
    sessionHookMocks.useSession.mockReturnValue({
      data: {
        id: 'session-1',
        channelType: 'web',
        chatId: 'chat-1',
        conversationKey: 'conv-1',
        transportChatId: null,
        state: 'ACTIVE',
        createdAt: null,
        updatedAt: '2026-03-22T10:00:00Z',
        messages: [],
      },
      isLoading: false,
      isError: false,
      error: null,
    });

    const html = renderToStaticMarkup(
      <StaticRouter location="/sessions/session-1/trace">
        <Routes>
          <Route path="/sessions/:sessionId/:tab" element={<SessionDetailsPage />} />
        </Routes>
      </StaticRouter>,
    );

    expect(html).toContain('Trace tab for session-1');
    expect(html).toContain('/sessions');
  });
});
