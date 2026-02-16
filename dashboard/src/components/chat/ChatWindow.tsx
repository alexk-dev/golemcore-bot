import { useEffect, useRef, useState, useCallback } from 'react';
import { Badge } from 'react-bootstrap';
import { useAuthStore } from '../../store/authStore';
import { useContextPanelStore } from '../../store/contextPanelStore';
import { listSessions, getSession } from '../../api/sessions';
import { getGoals } from '../../api/goals';
import { updatePreferences } from '../../api/settings';
import MessageBubble from './MessageBubble';
import ChatInput from './ChatInput';
import ContextPanel from './ContextPanel';

const SESSION_KEY = 'golem-chat-session-id';
const INITIAL_MESSAGES = 50;
const LOAD_MORE_COUNT = 50;
const GOALS_POLL_INTERVAL = 30000;

function getChatSessionId(): string {
  let id = localStorage.getItem(SESSION_KEY);
  if (id == null || id.length === 0) {
    id = crypto.randomUUID();
    localStorage.setItem(SESSION_KEY, id);
  }
  return id;
}

interface ChatMessage {
  role: string;
  content: string;
  model?: string | null;
  tier?: string | null;
  reasoning?: string | null;
}

interface ChatAttachmentPayload {
  type: 'image';
  name: string;
  mimeType: string;
  dataBase64: string;
}

interface OutboundChatPayload {
  text: string;
  attachments: ChatAttachmentPayload[];
}

interface AssistantHint {
  model?: string | null;
  tier?: string | null;
  reasoning?: string | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  latencyMs?: number | null;
  maxContextTokens?: number | null;
}

interface SocketMessage {
  type?: string;
  eventType?: string;
  text?: string;
  hint?: AssistantHint;
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
  const { panelOpen, togglePanel, setTurnMetadata, setGoals } = useContextPanelStore();

  // Load history from backend on mount
  useEffect(() => {
    if (historyLoaded) {return;}
    listSessions('web')
      .then((sessions) => {
        const sorted = sessions.sort((a, b) =>
          (b.updatedAt ?? b.createdAt ?? '').localeCompare(a.updatedAt ?? a.createdAt ?? '')
        );
        const match = sorted.find((s) => s.chatId === chatSessionId) ?? sorted[0];
        if (match != null) {
          if (match.chatId !== chatSessionId) {
            localStorage.setItem(SESSION_KEY, match.chatId);
          }
          return getSession(match.id);
        }
        return null;
      })
      .then((detail) => {
        if (detail != null && detail.messages.length > 0) {
          const history: ChatMessage[] = detail.messages
            .filter((m) => m.role === 'user' || m.role === 'assistant')
            .map((m) => ({
              role: m.role,
              content: m.content ?? '',
              model: m.model,
              tier: m.modelTier,
            }));
          setAllMessages(history);
          setVisibleStart(Math.max(0, history.length - INITIAL_MESSAGES));
        }
        setHistoryLoaded(true);
      })
      .catch(() => setHistoryLoaded(true));
  }, [chatSessionId, historyLoaded]);

  // Poll goals every 30s
  useEffect(() => {
    const fetchGoals = () => {
      getGoals()
        .then((res) => setGoals(res.goals, res.featureEnabled, res.autoModeEnabled))
        .catch(() => { /* ignore */ });
    };
    fetchGoals();
    const interval = setInterval(fetchGoals, GOALS_POLL_INTERVAL);
    return () => clearInterval(interval);
  }, [setGoals]);

  // Infinite scroll — load more on scroll to top
  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (el == null) {return;}

    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
    shouldAutoScroll.current = nearBottom;

    if (el.scrollTop < 50 && visibleStart > 0 && !loadingMore) {
      setLoadingMore(true);
      const prevScrollHeight = el.scrollHeight;
      const newStart = Math.max(0, visibleStart - LOAD_MORE_COUNT);
      setVisibleStart(newStart);

      requestAnimationFrame(() => {
        const newScrollHeight = el.scrollHeight;
        el.scrollTop = newScrollHeight - prevScrollHeight;
        setLoadingMore(false);
      });
    }
  }, [visibleStart, loadingMore]);

  const connect = useCallback(() => {
    if (token == null || token.length === 0) {return;}
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${protocol}//${window.location.host}/ws/chat?token=${token}`);

    ws.onopen = () => setConnected(true);
    ws.onclose = () => {
      setConnected(false);
      setTimeout(() => connect(), 3000);
    };
    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data) as SocketMessage;
        if (data.type === 'system_event' && data.eventType === 'typing') {
          setTyping(true);
          setTimeout(() => setTyping(false), 3000);
          return;
        }
        if (data.type === 'assistant_chunk' || data.type === 'assistant_done') {
          setTyping(false);

          // Extract hints for context panel
          if (data.hint != null) {
            setTurnMetadata({
              model: data.hint.model ?? null,
              tier: data.hint.tier ?? null,
              reasoning: data.hint.reasoning ?? null,
              inputTokens: data.hint.inputTokens ?? null,
              outputTokens: data.hint.outputTokens ?? null,
              totalTokens: data.hint.totalTokens ?? null,
              latencyMs: data.hint.latencyMs ?? null,
              maxContextTokens: data.hint.maxContextTokens ?? null,
            });
          }

          setAllMessages((prev) => {
            const last = prev.length > 0 ? prev[prev.length - 1] : undefined;
            const chunkModel = data.hint?.model ?? null;
            const chunkTier = data.hint?.tier ?? null;
            const chunkReasoning = data.hint?.reasoning ?? null;
            if (last?.role === 'assistant' && data.type === 'assistant_chunk') {
              return [...prev.slice(0, -1), {
                role: 'assistant',
                content: `${last.content}${data.text ?? ''}`,
                model: chunkModel ?? last.model ?? null,
                tier: chunkTier ?? last.tier ?? null,
                reasoning: chunkReasoning ?? last.reasoning ?? null,
              }];
            }
            return [...prev, {
              role: 'assistant',
              content: data.text ?? '',
              model: chunkModel,
              tier: chunkTier,
              reasoning: chunkReasoning,
            }];
          });
        }
      } catch {
        // ignore parse errors
      }
    };

    wsRef.current = ws;
    return () => ws.close();
  }, [token, setTurnMetadata]);

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

  const handleSend = ({ text, attachments }: OutboundChatPayload) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      const trimmed = text.trim();
      const fallback = attachments.length > 0
        ? `[${attachments.length} image attachment${attachments.length > 1 ? 's' : ''}]`
        : '';
      const newMsg = { role: 'user', content: trimmed.length > 0 ? trimmed : fallback };
      setAllMessages((prev) => [...prev, newMsg]);
      shouldAutoScroll.current = true;
      wsRef.current.send(JSON.stringify({
        text,
        attachments,
        sessionId: chatSessionId,
      }));
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
    <div className="chat-page-layout">
      <div className="chat-container">
        {/* Toolbar */}
        <div className="chat-toolbar">
          <div className="chat-toolbar-inner d-flex align-items-center">
            <div className="d-flex align-items-center gap-2 flex-grow-1">
              <span className={`status-dot ${connected ? 'online' : 'offline'}`} />
              <small className="text-body-secondary">{connected ? 'Connected' : 'Reconnecting...'}</small>
            </div>
            <button
              className="btn btn-sm btn-secondary panel-toggle-btn"
              onClick={togglePanel}
              title={panelOpen ? 'Hide context panel' : 'Show context panel'}
            >
              {panelOpen ? '\u00BB' : '\u00AB'}
            </button>
          </div>
        </div>

        {/* Messages */}
        <div className="chat-window" ref={scrollRef} onScroll={handleScroll}>
          <div className="chat-content-shell">
            {hasMore && (
              <div className="text-center py-2">
                {loadingMore ? (
                  <small className="text-body-secondary">Loading...</small>
                ) : (
                  <Badge bg="secondary" className="cursor-pointer"
                    onClick={() => setVisibleStart(Math.max(0, visibleStart - LOAD_MORE_COUNT))}>
                    Load earlier messages
                  </Badge>
                )}
              </div>
            )}
            {visibleMessages.map((msg, i) => (
              <MessageBubble
                key={visibleStart + i}
                role={msg.role}
                content={msg.content}
                model={msg.model ?? null}
                tier={msg.tier ?? null}
                reasoning={msg.reasoning ?? null}
              />
            ))}
            {typing && (
              <div className="typing-indicator">
                <span /><span /><span />
              </div>
            )}
          </div>
          <div ref={bottomRef} />
        </div>

        {/* Input */}
        <ChatInput onSend={handleSend} disabled={!connected} />
      </div>

      <ContextPanel
        tier={tier}
        tierForce={tierForce}
        onTierChange={handleTierChange}
        onForceChange={handleForceChange}
      />
    </div>
  );
}
