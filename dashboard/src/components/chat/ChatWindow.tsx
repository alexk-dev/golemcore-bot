import { useEffect, useRef, useState, useCallback } from 'react';
import { Badge, Form } from 'react-bootstrap';
import { useAuthStore } from '../../store/authStore';
import { listSessions, getSession } from '../../api/sessions';
import { updatePreferences } from '../../api/settings';
import MessageBubble from './MessageBubble';
import ChatInput from './ChatInput';

const SESSION_KEY = 'golem-chat-session-id';
const INITIAL_MESSAGES = 50;
const LOAD_MORE_COUNT = 50;

function getChatSessionId(): string {
  let id = localStorage.getItem(SESSION_KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(SESSION_KEY, id);
  }
  return id;
}

interface ChatMessage {
  role: string;
  content: string;
}

export default function ChatWindow() {
  const [allMessages, setAllMessages] = useState<ChatMessage[]>([]);
  const [visibleStart, setVisibleStart] = useState(0);
  const [connected, setConnected] = useState(false);
  const [typing, setTyping] = useState(false);
  const [historyLoaded, setHistoryLoaded] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [tier, setTier] = useState('balanced');
  const [tierForce, setTierForce] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const token = useAuthStore((s) => s.accessToken);
  const chatSessionId = useRef(getChatSessionId()).current;
  const shouldAutoScroll = useRef(true);

  // Load history from backend on mount
  useEffect(() => {
    if (historyLoaded) return;
    listSessions('web')
      .then((sessions) => {
        const sorted = sessions.sort((a, b) =>
          (b.updatedAt ?? b.createdAt ?? '').localeCompare(a.updatedAt ?? a.createdAt ?? '')
        );
        // Find matching session or the latest active one
        const match = sorted.find((s) => s.chatId === chatSessionId) ?? sorted[0];
        if (match) {
          // If we found a different session, update our session ID
          if (match.chatId !== chatSessionId) {
            localStorage.setItem(SESSION_KEY, match.chatId);
          }
          return getSession(match.id);
        }
        return null;
      })
      .then((detail) => {
        if (detail && detail.messages.length > 0) {
          const history: ChatMessage[] = detail.messages
            .filter((m) => m.role === 'user' || m.role === 'assistant')
            .map((m) => ({ role: m.role, content: m.content }));
          setAllMessages(history);
          // Show only the last N messages initially
          setVisibleStart(Math.max(0, history.length - INITIAL_MESSAGES));
        }
        setHistoryLoaded(true);
      })
      .catch(() => setHistoryLoaded(true));
  }, [chatSessionId, historyLoaded]);

  // Infinite scroll — load more on scroll to top
  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;

    // Check if user scrolled near bottom
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
    shouldAutoScroll.current = nearBottom;

    // Load more when scrolled to top
    if (el.scrollTop < 50 && visibleStart > 0 && !loadingMore) {
      setLoadingMore(true);
      const prevScrollHeight = el.scrollHeight;
      const newStart = Math.max(0, visibleStart - LOAD_MORE_COUNT);
      setVisibleStart(newStart);

      // Preserve scroll position after loading more
      requestAnimationFrame(() => {
        const newScrollHeight = el.scrollHeight;
        el.scrollTop = newScrollHeight - prevScrollHeight;
        setLoadingMore(false);
      });
    }
  }, [visibleStart, loadingMore]);

  const connect = useCallback(() => {
    if (!token) return;
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${protocol}//${window.location.host}/ws/chat?token=${token}`);

    ws.onopen = () => setConnected(true);
    ws.onclose = () => {
      setConnected(false);
      // Auto-reconnect after 3s
      setTimeout(() => connect(), 3000);
    };
    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === 'system_event' && data.eventType === 'typing') {
          setTyping(true);
          setTimeout(() => setTyping(false), 3000);
          return;
        }
        if (data.type === 'assistant_chunk' || data.type === 'assistant_done') {
          setTyping(false);
          setAllMessages((prev) => {
            const last = prev[prev.length - 1];
            if (last && last.role === 'assistant' && data.type === 'assistant_chunk') {
              return [...prev.slice(0, -1), { role: 'assistant', content: last.content + data.text }];
            }
            return [...prev, { role: 'assistant', content: data.text }];
          });
        }
      } catch {
        // ignore parse errors
      }
    };

    wsRef.current = ws;
    return () => ws.close();
  }, [token]);

  useEffect(() => {
    const cleanup = connect();
    return cleanup;
  }, [connect]);

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    if (shouldAutoScroll.current) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [allMessages, typing]);

  const handleSend = (text: string) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      const newMsg = { role: 'user', content: text };
      setAllMessages((prev) => [...prev, newMsg]);
      shouldAutoScroll.current = true;
      wsRef.current.send(JSON.stringify({ text, sessionId: chatSessionId }));
    }
  };

  const handleTierChange = async (newTier: string) => {
    setTier(newTier);
    try {
      await updatePreferences({ modelTier: newTier, tierForce });
    } catch { /* ignore */ }
  };

  const handleForceChange = async (force: boolean) => {
    setTierForce(force);
    try {
      await updatePreferences({ modelTier: tier, tierForce: force });
    } catch { /* ignore */ }
  };

  const visibleMessages = allMessages.slice(visibleStart);
  const hasMore = visibleStart > 0;

  return (
    <div className="chat-container">
      {/* Toolbar */}
      <div className="chat-toolbar">
        <div className="d-flex align-items-center gap-2 flex-grow-1">
          <span className={`status-dot ${connected ? 'online' : 'offline'}`} />
          <small className="text-muted">{connected ? 'Connected' : 'Reconnecting...'}</small>
        </div>
        <div className="d-flex align-items-center gap-2">
          <Form.Select
            size="sm"
            value={tier}
            onChange={(e) => handleTierChange(e.target.value)}
            style={{ width: 120 }}
          >
            <option value="balanced">Balanced</option>
            <option value="smart">Smart</option>
            <option value="coding">Coding</option>
            <option value="deep">Deep</option>
          </Form.Select>
          <Form.Check
            type="switch"
            label={<small className="text-muted">Force</small>}
            checked={tierForce}
            onChange={(e) => handleForceChange(e.target.checked)}
          />
        </div>
      </div>

      {/* Messages */}
      <div className="chat-window" ref={scrollRef} onScroll={handleScroll}>
        {hasMore && (
          <div className="text-center py-2">
            {loadingMore ? (
              <small className="text-muted">Loading...</small>
            ) : (
              <Badge bg="secondary" className="cursor-pointer" style={{ cursor: 'pointer' }}
                onClick={() => setVisibleStart(Math.max(0, visibleStart - LOAD_MORE_COUNT))}>
                Load earlier messages
              </Badge>
            )}
          </div>
        )}
        {visibleMessages.map((msg, i) => (
          <MessageBubble key={visibleStart + i} role={msg.role} content={msg.content} />
        ))}
        {typing && (
          <div className="typing-indicator">
            <span /><span /><span />
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {/* Input */}
      <ChatInput onSend={handleSend} disabled={!connected} />
    </div>
  );
}
