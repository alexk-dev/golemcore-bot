import { useEffect, useRef, useState, useCallback } from 'react';
import { Offcanvas } from 'react-bootstrap';
import { FiLayout, FiMessageSquare, FiPlus } from 'react-icons/fi';
import { useAuthStore } from '../../store/authStore';
import { useChatSessionStore } from '../../store/chatSessionStore';
import { useContextPanelStore } from '../../store/contextPanelStore';
import { createSession, getActiveSession, listSessions, getSession, setActiveSession } from '../../api/sessions';
import { getGoals } from '../../api/goals';
import { updatePreferences } from '../../api/settings';
import MessageBubble from './MessageBubble';
import ChatInput from './ChatInput';
import ContextPanel from './ContextPanel';
import { createUuid } from '../../utils/uuid';
import { isLegacyCompatibleConversationKey, normalizeConversationKey } from '../../utils/conversationKey';

const INITIAL_MESSAGES = 50;
const LOAD_MORE_COUNT = 50;
const GOALS_POLL_INTERVAL = 30000;
const RECONNECT_DELAY_MS = 3000;
const TYPING_RESET_MS = 3000;
const STARTER_PROMPTS = [
  {
    key: 'plan-next',
    label: 'Plan next step',
    text: 'Review the current status and suggest the next three prioritized steps.',
  },
  {
    key: 'risk-check',
    label: 'Find risks',
    text: 'Analyze the current work and list the most likely risks with mitigations.',
  },
  {
    key: 'draft-update',
    label: 'Draft update',
    text: 'Write a concise progress update I can send to the team.',
  },
];

const EMPTY_TURN_METADATA = {
  model: null,
  tier: null,
  reasoning: null,
  inputTokens: null,
  outputTokens: null,
  totalTokens: null,
  latencyMs: null,
  maxContextTokens: null,
};

function getLocalCommand(text: string): 'new' | 'reset' | null {
  const command = (text.split(/\s+/)[0] ?? '').toLowerCase();
  if (command === '/new') {
    return 'new';
  }
  if (command === '/reset') {
    return 'reset';
  }
  return null;
}

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  model?: string | null;
  tier?: string | null;
  reasoning?: string | null;
  status?: 'pending' | 'failed';
  outbound?: OutboundChatPayload;
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
  sessionId?: string;
  hint?: AssistantHint;
}

export default function ChatWindow() {
  const chatSessionId = useChatSessionStore((s) => s.activeSessionId);
  const setChatSessionId = useChatSessionStore((s) => s.setActiveSessionId);
  const clientInstanceId = useChatSessionStore((s) => s.clientInstanceId);
  const [allMessages, setAllMessages] = useState<ChatMessage[]>([]);
  const [visibleStart, setVisibleStart] = useState(0);
  const [connected, setConnected] = useState(false);
  const [typing, setTyping] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [historyReloadTick, setHistoryReloadTick] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  const [tier, setTier] = useState('balanced');
  const [tierForce, setTierForce] = useState(false);

  const wsRef = useRef<WebSocket | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const chatSessionIdRef = useRef(chatSessionId);
  const shouldAutoScroll = useRef(true);
  const lastUpdateWasChunkRef = useRef(false);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const typingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const token = useAuthStore((s) => s.accessToken);
  const {
    panelOpen,
    togglePanel,
    mobileDrawerOpen,
    openMobileDrawer,
    closeMobileDrawer,
    setTurnMetadata,
    setGoals,
  } = useContextPanelStore();

  useEffect(() => {
    chatSessionIdRef.current = chatSessionId;
  }, [chatSessionId]);

  const clearReconnectTimer = useCallback(() => {
    if (reconnectTimerRef.current != null) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
  }, []);

  const clearTypingTimer = useCallback(() => {
    if (typingTimerRef.current != null) {
      clearTimeout(typingTimerRef.current);
      typingTimerRef.current = null;
    }
  }, []);

  const resetTurnMetadata = useCallback(() => {
    setTurnMetadata(EMPTY_TURN_METADATA);
  }, [setTurnMetadata]);

  const resetConversationState = useCallback(() => {
    setAllMessages([]);
    setVisibleStart(0);
    resetTurnMetadata();
  }, [resetTurnMetadata]);

  const startNewConversation = useCallback(() => {
    const newSessionId = createUuid();
    setChatSessionId(newSessionId);
    resetConversationState();
    createSession({
      channelType: 'web',
      clientInstanceId,
      conversationKey: newSessionId,
      activate: true,
    }).catch(() => {
      // ignore creation failures; session will still be created lazily on first message
    });
  }, [clientInstanceId, resetConversationState, setChatSessionId]);

  const setUserMessageStatus = useCallback((id: string, status: 'pending' | 'failed' | null): void => {
    setAllMessages((prev) => {
      const index = prev.findIndex((message) => message.id === id && message.role === 'user');
      if (index < 0) {
        return prev;
      }

      const next = [...prev];
      const current = next[index];
      if (status == null) {
        next[index] = { ...current, status: undefined, outbound: undefined };
      } else {
        next[index] = { ...current, status };
      }
      return next;
    });
  }, []);

  const markFirstPendingAsSent = useCallback(() => {
    setAllMessages((prev) => {
      const index = prev.findIndex((message) => message.role === 'user' && message.status === 'pending');
      if (index < 0) {
        return prev;
      }

      const next = [...prev];
      const target = next[index];
      next[index] = { ...target, status: undefined, outbound: undefined };
      return next;
    });
  }, []);

  const markPendingAsFailed = useCallback(() => {
    setAllMessages((prev) => {
      let changed = false;
      const next = prev.map((message) => {
        if (message.role === 'user' && message.status === 'pending') {
          changed = true;
          return { ...message, status: 'failed' as const };
        }
        return message;
      });
      return changed ? next : prev;
    });
  }, []);

  const sendPayload = useCallback((id: string, payload: OutboundChatPayload): boolean => {
    const socket = wsRef.current;
    if (socket?.readyState !== WebSocket.OPEN) {
      setUserMessageStatus(id, 'failed');
      return false;
    }

    try {
      socket.send(JSON.stringify({
        text: payload.text,
        attachments: payload.attachments,
        sessionId: chatSessionId,
      }));
      return true;
    } catch {
      setUserMessageStatus(id, 'failed');
      return false;
    }
  }, [chatSessionId, setUserMessageStatus]);

  useEffect(() => {
    resetTurnMetadata();
  }, [chatSessionId, resetTurnMetadata]);

  // Keep server-side active pointer in sync with local chat session selection.
  useEffect(() => {
    if (!isLegacyCompatibleConversationKey(chatSessionId)) {
      return;
    }
    setActiveSession({
      channelType: 'web',
      clientInstanceId,
      conversationKey: chatSessionId,
    }).catch(() => {
      // ignore pointer persistence failures
    });
  }, [chatSessionId, clientInstanceId]);

  // Resolve active conversation from backend on mount to keep sidebar/chat consistent across pages.
  useEffect(() => {
    let cancelled = false;
    getActiveSession('web', clientInstanceId)
      .then((activeSession) => {
        if (cancelled) {
          return;
        }
        const nextConversationKey = normalizeConversationKey(activeSession.conversationKey);
        if (nextConversationKey == null || !isLegacyCompatibleConversationKey(nextConversationKey)) {
          startNewConversation();
          return;
        }
        if (nextConversationKey === chatSessionIdRef.current) {
          return;
        }
        setChatSessionId(nextConversationKey);
        resetConversationState();
      })
      .catch(() => {
        // ignore active session resolution failures
      });

    return () => {
      cancelled = true;
    };
  }, [clientInstanceId, resetConversationState, setChatSessionId, startNewConversation]);

  // Load current session history
  useEffect(() => {
    let cancelled = false;
    setHistoryLoading(true);
    setHistoryError(null);

    listSessions('web')
      .then((sessions) => {
        const match = sessions.find((session) => session.conversationKey === chatSessionId || session.chatId === chatSessionId);
        if (match == null) {
          return null;
        }
        return getSession(match.id);
      })
      .then((detail) => {
        if (cancelled) {
          return;
        }

        if (detail == null) {
          setAllMessages([]);
          setVisibleStart(0);
          return;
        }

        const history: ChatMessage[] = detail.messages
          .filter((message) => message.role === 'user' || message.role === 'assistant')
          .map((message) => ({
            id: message.id,
            role: message.role as 'user' | 'assistant',
            content: message.content ?? '',
            model: message.model,
            tier: message.modelTier,
          }));

        setAllMessages(history);
        setVisibleStart(Math.max(0, history.length - INITIAL_MESSAGES));
      })
      .catch(() => {
        if (!cancelled) {
          setHistoryError('Failed to load chat history.');
          setAllMessages([]);
          setVisibleStart(0);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setHistoryLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [chatSessionId, historyReloadTick]);

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

  // WebSocket lifecycle with safe reconnect
  useEffect(() => {
    clearReconnectTimer();
    clearTypingTimer();

    if (token == null || token.length === 0) {
      setConnected(false);
      setTyping(false);
      wsRef.current?.close();
      wsRef.current = null;
      return;
    }

    let disposed = false;

    const openSocket = () => {
      if (disposed) {
        return;
      }

      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const ws = new WebSocket(`${protocol}//${window.location.host}/ws/chat?token=${token}`);
      wsRef.current = ws;

      ws.onopen = () => {
        if (disposed) {
          ws.close();
          return;
        }
        clearReconnectTimer();
        setConnected(true);
      };

      ws.onclose = () => {
        if (disposed) {
          return;
        }
        setConnected(false);
        setTyping(false);
        clearTypingTimer();
        markPendingAsFailed();
        clearReconnectTimer();
        reconnectTimerRef.current = setTimeout(() => {
          openSocket();
        }, RECONNECT_DELAY_MS);
      };

      ws.onmessage = (event) => {
        if (disposed) {
          return;
        }

        try {
          const data = JSON.parse(event.data) as SocketMessage;

          if (data.sessionId != null && data.sessionId.length > 0 && data.sessionId !== chatSessionIdRef.current) {
            return;
          }

          if (data.type === 'system_event' && data.eventType === 'typing') {
            setTyping(true);
            markFirstPendingAsSent();
            clearTypingTimer();
            typingTimerRef.current = setTimeout(() => {
              if (!disposed) {
                setTyping(false);
              }
            }, TYPING_RESET_MS);
            return;
          }

          if (data.type === 'assistant_chunk' || data.type === 'assistant_done') {
            setTyping(false);
            clearTypingTimer();
            markFirstPendingAsSent();
            lastUpdateWasChunkRef.current = data.type === 'assistant_chunk';

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
                  ...last,
                  content: `${last.content}${data.text ?? ''}`,
                  model: chunkModel ?? last.model ?? null,
                  tier: chunkTier ?? last.tier ?? null,
                  reasoning: chunkReasoning ?? last.reasoning ?? null,
                }];
              }

              return [...prev, {
                id: createUuid(),
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
    };

    openSocket();

    return () => {
      disposed = true;
      clearReconnectTimer();
      clearTypingTimer();
      setConnected(false);
      setTyping(false);
      const socket = wsRef.current;
      wsRef.current = null;
      socket?.close();
    };
  }, [token, clearReconnectTimer, clearTypingTimer, markPendingAsFailed, markFirstPendingAsSent, setTurnMetadata]);

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    if (!shouldAutoScroll.current) {
      return;
    }

    const behavior: ScrollBehavior = lastUpdateWasChunkRef.current ? 'auto' : 'smooth';
    bottomRef.current?.scrollIntoView({ behavior });
  }, [allMessages, typing]);

  const handleRetry = useCallback((messageId: string) => {
    let payloadToRetry: OutboundChatPayload | null = null;

    setAllMessages((prev) => prev.map((message) => {
      if (message.id !== messageId || message.role !== 'user' || message.outbound == null) {
        return message;
      }
      payloadToRetry = message.outbound;
      return { ...message, status: 'pending' as const };
    }));

    if (payloadToRetry == null) {
      return;
    }

    shouldAutoScroll.current = true;
    lastUpdateWasChunkRef.current = false;
    sendPayload(messageId, payloadToRetry);
  }, [sendPayload]);

  const handleSend = useCallback(({ text, attachments }: OutboundChatPayload) => {
    const trimmed = text.trim();
    const localCommand = getLocalCommand(trimmed);
    if (localCommand === 'new' && attachments.length === 0) {
      startNewConversation();
      return;
    }

    const fallback = attachments.length > 0
      ? `[${attachments.length} image attachment${attachments.length > 1 ? 's' : ''}]`
      : '';

    const messageId = createUuid();
    const outboundPayload: OutboundChatPayload = {
      text: trimmed,
      attachments,
    };

    setAllMessages((prev) => [...prev, {
      id: messageId,
      role: 'user',
      content: trimmed.length > 0 ? trimmed : fallback,
      status: 'pending',
      outbound: outboundPayload,
    }]);

    shouldAutoScroll.current = true;
    lastUpdateWasChunkRef.current = false;

    const sent = sendPayload(messageId, outboundPayload);

    if (localCommand === 'reset' && sent) {
      resetConversationState();
    }
  }, [resetConversationState, sendPayload, startNewConversation]);

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
        <div className="chat-toolbar">
          <div className="chat-toolbar-inner">
            <div className="chat-toolbar-main">
              <div className="chat-toolbar-title-group">
                <div className="chat-toolbar-title">
                  <FiMessageSquare aria-hidden="true" />
                  <span>Workspace Chat</span>
                </div>
                <small className="chat-toolbar-subtitle">
                  Session: <span className="font-mono">{chatSessionId.slice(0, 8)}</span>
                </small>
              </div>
              <div className="chat-toolbar-status" aria-live="polite">
                <span className={`status-dot ${connected ? 'online' : 'offline'}`} aria-hidden="true" />
                <small className="text-body-secondary">{connected ? 'Connected' : 'Reconnecting...'}</small>
              </div>
            </div>
            <div className="chat-toolbar-actions">
              <button
                type="button"
                className="btn btn-sm btn-secondary chat-toolbar-btn"
                onClick={startNewConversation}
                title="Start a new chat session"
                aria-label="Start a new chat session"
              >
                <FiPlus aria-hidden="true" />
                <span>New chat</span>
              </button>
              <button
                type="button"
                className="btn btn-sm btn-secondary chat-toolbar-btn panel-toggle-btn"
                onClick={() => {
                  if (window.innerWidth > 992) {
                    togglePanel();
                  } else {
                    openMobileDrawer();
                  }
                }}
                title={panelOpen ? 'Hide context panel' : 'Show context panel'}
                aria-label={panelOpen ? 'Hide context panel' : 'Show context panel'}
              >
                <FiLayout aria-hidden="true" />
                <span>{panelOpen ? 'Hide context' : 'Show context'}</span>
              </button>
            </div>
          </div>
        </div>

        <div
          className="chat-window"
          ref={scrollRef}
          onScroll={handleScroll}
          role="log"
          aria-relevant="additions text"
          aria-busy={typing}
        >
          <div className="chat-content-shell">
            {historyLoading && (
              <div className="chat-history-state" role="status" aria-live="polite">
                <span className="spinner-border spinner-border-sm" aria-hidden="true" />
                <small className="text-body-secondary">Loading history...</small>
              </div>
            )}

            {!historyLoading && historyError != null && (
              <div className="chat-history-state" role="status" aria-live="polite">
                <small className="text-danger">{historyError}</small>
                <button
                  type="button"
                  className="btn btn-sm btn-secondary"
                  onClick={() => setHistoryReloadTick((v) => v + 1)}
                >
                  Retry
                </button>
              </div>
            )}

            {!historyLoading && hasMore && (
              <div className="text-center py-2">
                {loadingMore ? (
                  <small className="text-body-secondary">Loading...</small>
                ) : (
                  <button
                    type="button"
                    className="btn btn-sm btn-secondary chat-history-load-btn"
                    onClick={() => setVisibleStart(Math.max(0, visibleStart - LOAD_MORE_COUNT))}
                  >
                    Load earlier messages
                  </button>
                )}
              </div>
            )}

            {!historyLoading && historyError == null && visibleMessages.length === 0 && (
              <div className="chat-empty-state" role="status" aria-live="polite">
                <div className="chat-empty-state-icon" aria-hidden="true">
                  <FiMessageSquare />
                </div>
                <h2 className="chat-empty-state-title">Start a focused conversation</h2>
                <p className="chat-empty-state-text">
                  Pick a starter prompt or type your own message below.
                </p>
                <div className="chat-starter-grid">
                  {STARTER_PROMPTS.map((prompt) => (
                    <button
                      key={prompt.key}
                      type="button"
                      className="chat-starter-card"
                      onClick={() => handleSend({ text: prompt.text, attachments: [] })}
                    >
                      <span className="chat-starter-title">{prompt.label}</span>
                      <span className="chat-starter-text">{prompt.text}</span>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {visibleMessages.map((msg) => (
              <MessageBubble
                key={msg.id}
                role={msg.role}
                content={msg.content}
                model={msg.model ?? null}
                tier={msg.tier ?? null}
                reasoning={msg.reasoning ?? null}
                clientStatus={msg.role === 'user' ? msg.status : undefined}
                onRetry={msg.role === 'user' && msg.status === 'failed' && msg.outbound != null
                  ? () => handleRetry(msg.id)
                  : undefined}
              />
            ))}

            {typing && (
              <div className="typing-indicator" role="status" aria-live="polite" aria-label="Assistant is typing">
                <span className="typing-label">Thinking</span>
                <span className="typing-dots" aria-hidden="true">
                  <span /><span /><span />
                </span>
              </div>
            )}
          </div>
          <div ref={bottomRef} />
        </div>

        <ChatInput
          onSend={handleSend}
          running={typing}
          onStop={() => {
            if (wsRef.current?.readyState === WebSocket.OPEN) {
              wsRef.current.send(JSON.stringify({ text: '/stop', sessionId: chatSessionId }));
            }
          }}
        />
      </div>

      <ContextPanel
        tier={tier}
        tierForce={tierForce}
        chatSessionId={chatSessionId}
        onTierChange={handleTierChange}
        onForceChange={handleForceChange}
      />

      <Offcanvas
        show={mobileDrawerOpen}
        onHide={closeMobileDrawer}
        placement="end"
        className="context-panel-offcanvas"
      >
        <Offcanvas.Header closeButton>
          <Offcanvas.Title>Context</Offcanvas.Title>
        </Offcanvas.Header>
        <Offcanvas.Body className="p-0">
          <ContextPanel
            tier={tier}
            tierForce={tierForce}
            chatSessionId={chatSessionId}
            onTierChange={handleTierChange}
            onForceChange={handleForceChange}
            forceOpen
          />
        </Offcanvas.Body>
      </Offcanvas>
    </div>
  );
}
