import { useCallback, useEffect, useMemo, useRef, useState, type ReactElement } from 'react';
import { Offcanvas } from '../ui/tailwind-components';
import { useChatSessionStore } from '../../store/chatSessionStore';
import { useContextPanelStore } from '../../store/contextPanelStore';
import { useChatRuntimeStore } from '../../store/chatRuntimeStore';
import { useCreateSession } from '../../hooks/useSessions';
import { useModelsConfig } from '../../hooks/useModels';
import { getGoals } from '../../api/goals';
import { getSettings, updatePreferences } from '../../api/settings';
import { createUuid } from '../../utils/uuid';
import ChatInput from './ChatInput';
import ContextPanel from './ContextPanel';
import { ChatConversation } from './ChatConversation';
import { ChatToolbar } from './ChatToolbar';
import type { OutboundChatPayload } from './chatInputTypes';
import { useChatSessionHistory } from './useChatSessionHistory';
import { useTelemetry } from '../../lib/telemetry/TelemetryContext';
import { normalizeExplicitModelTier } from '../../lib/modelTiers';

const GOALS_POLL_INTERVAL = 30000;
const EMPTY_TURN_METADATA = {
  model: null,
  tier: null,
  reasoning: null,
  inputTokens: null,
  outputTokens: null,
  totalTokens: null,
  latencyMs: null,
  maxContextTokens: null,
  fileChanges: [],
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

function normalizeTier(value: string | null | undefined): string {
  return normalizeExplicitModelTier(value);
}

export default function ChatWindow(): ReactElement {
  const chatSessionId = useChatSessionStore((state) => state.activeSessionId);
  const setChatSessionId = useChatSessionStore((state) => state.setActiveSessionId);
  const clientInstanceId = useChatSessionStore((state) => state.clientInstanceId);
  const createSessionMutation = useCreateSession();
  const { data: modelsConfig } = useModelsConfig();
  const connectionState = useChatRuntimeStore((state) => state.connectionState);
  const appendOptimisticUserMessage = useChatRuntimeStore((state) => state.appendOptimisticUserMessage);
  const retryUserMessage = useChatRuntimeStore((state) => state.retryUserMessage);
  const sendMessage = useChatRuntimeStore((state) => state.sendMessage);
  const stopSession = useChatRuntimeStore((state) => state.stopSession);
  const resetSession = useChatRuntimeStore((state) => state.resetSession);
  const {
    panelOpen,
    togglePanel,
    mobileDrawerOpen,
    openMobileDrawer,
    closeMobileDrawer,
    setTurnMetadata,
    setGoals,
  } = useContextPanelStore();
  const { sessionState, loadEarlierMessages, reloadHistory } = useChatSessionHistory(chatSessionId);
  const telemetry = useTelemetry();
  const [tier, setTier] = useState('balanced');
  const [tierForce, setTierForce] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);
  const shouldAutoScrollRef = useRef(true);
  const prependScrollHeightRef = useRef<number | null>(null);
  const preferenceUpdateQueueRef = useRef<Promise<void>>(Promise.resolve());
  const hasLocalPreferenceChangesRef = useRef(false);

  const isConnected = connectionState === 'connected';
  const isLoadingEarlier = sessionState.historyLoading && sessionState.messages.length > 0;
  const running = sessionState.running || sessionState.typing;

  useEffect(() => {
    // Keep tier controls in sync with persisted user preferences used by backend tier resolution.
    let cancelled = false;
    getSettings()
      .then((settings) => {
        if (cancelled || hasLocalPreferenceChangesRef.current) {
          return;
        }
        setTier(normalizeTier(settings.modelTier));
        setTierForce(settings.tierForce === true);
      })
      .catch(() => {
        // Ignore settings bootstrap failures; chat can still operate with defaults.
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    // Keep goals/context side panel fresh while the chat workspace is open.
    const fetchGoals = (): void => {
      getGoals()
        .then((response) => setGoals(response.goals, response.featureEnabled, response.autoModeEnabled))
        .catch(() => {
          // Keep polling resilient to intermittent API failures.
        });
    };

    fetchGoals();
    const interval = setInterval(fetchGoals, GOALS_POLL_INTERVAL);
    return () => clearInterval(interval);
  }, [setGoals]);

  useEffect(() => {
    // Restore scroll position on prepends and keep the viewport pinned only when the user is already at the bottom.
    const element = scrollRef.current;
    if (element == null) {
      return;
    }

    if (prependScrollHeightRef.current != null) {
      const previousScrollHeight = prependScrollHeightRef.current;
      prependScrollHeightRef.current = null;
      element.scrollTop = element.scrollHeight - previousScrollHeight;
      return;
    }

    if (shouldAutoScrollRef.current) {
      element.scrollTop = element.scrollHeight;
    }
  }, [sessionState.messages, sessionState.typing]);

  const enqueuePreferencesUpdate = useCallback((prefsPatch: Record<string, unknown>): void => {
    preferenceUpdateQueueRef.current = preferenceUpdateQueueRef.current
      .catch(() => {
        // Ignore failed writes and keep the queue alive for later updates.
      })
      .then(async () => {
        const settings = await updatePreferences(prefsPatch);
        setTier(normalizeTier(settings.modelTier));
        setTierForce(settings.tierForce === true);
      })
      .catch(() => {
        // Ignore preference update failures; the optimistic UI state remains local.
      });
  }, []);

  const startNewConversation = useCallback((): void => {
    const newSessionId = createUuid();
    resetSession(newSessionId);
    setChatSessionId(newSessionId);
    createSessionMutation.mutate({
      channelType: 'web',
      clientInstanceId,
      conversationKey: newSessionId,
      activate: true,
    }, {
      onError: () => {
        // Keep the local conversation even if eager session creation fails.
      },
    });
  }, [clientInstanceId, createSessionMutation, resetSession, setChatSessionId]);

  const handleSend = useCallback((payload: OutboundChatPayload): void => {
    const trimmed = payload.text.trim();
    const localCommand = getLocalCommand(trimmed);
    if (localCommand === 'new' && payload.attachments.length === 0) {
      startNewConversation();
      return;
    }

    const fallback = payload.attachments.length > 0
      ? `[${payload.attachments.length} image attachment${payload.attachments.length > 1 ? 's' : ''}]`
      : '';

    const messageId = createUuid();
    const outboundPayload: OutboundChatPayload = {
      text: trimmed,
      attachments: payload.attachments,
    };

    telemetry.recordCounter('chat_send_count');
    appendOptimisticUserMessage(chatSessionId, {
      id: messageId,
      role: 'user',
      content: trimmed.length > 0 ? trimmed : fallback,
      model: null,
      tier: null,
      skill: null,
      reasoning: null,
      attachments: [],
      clientStatus: 'pending',
      outbound: outboundPayload,
      clientMessageId: messageId,
      persisted: false,
    });

    shouldAutoScrollRef.current = true;
    const sent = sendMessage(chatSessionId, clientInstanceId, messageId, outboundPayload);

    if (localCommand === 'reset' && sent) {
      resetSession(chatSessionId);
    }
  }, [appendOptimisticUserMessage, chatSessionId, clientInstanceId, resetSession, sendMessage, startNewConversation, telemetry]);

  const handleRetry = useCallback((messageId: string): void => {
    const outbound = retryUserMessage(chatSessionId, messageId);
    if (outbound == null) {
      return;
    }

    shouldAutoScrollRef.current = true;
    sendMessage(chatSessionId, clientInstanceId, messageId, outbound);
  }, [chatSessionId, clientInstanceId, retryUserMessage, sendMessage]);

  const handleScroll = useCallback((): void => {
    const element = scrollRef.current;
    if (element == null) {
      return;
    }

    shouldAutoScrollRef.current = element.scrollHeight - element.scrollTop - element.clientHeight < 100;

    if (element.scrollTop >= 50 || isLoadingEarlier || !sessionState.hasMoreHistory) {
      return;
    }

    prependScrollHeightRef.current = element.scrollHeight;
    void loadEarlierMessages().then((loaded) => {
      if (!loaded) {
        prependScrollHeightRef.current = null;
      }
    });
  }, [isLoadingEarlier, loadEarlierMessages, sessionState.hasMoreHistory]);

  const handleLoadEarlierMessages = useCallback((): void => {
    const element = scrollRef.current;
    if (element != null) {
      prependScrollHeightRef.current = element.scrollHeight;
    }
    void loadEarlierMessages().then((loaded) => {
      if (!loaded) {
        prependScrollHeightRef.current = null;
      }
    });
  }, [loadEarlierMessages]);

  const handleTierChange = useCallback((newTier: string): void => {
    const normalizedTier = normalizeTier(newTier);
    hasLocalPreferenceChangesRef.current = true;
    telemetry.recordKeyedCounter('tier_select_count_by_tier', normalizedTier);
    setTier(normalizedTier);
    setTurnMetadata(EMPTY_TURN_METADATA);
    enqueuePreferencesUpdate({ modelTier: normalizedTier });
  }, [enqueuePreferencesUpdate, setTurnMetadata, telemetry]);

  const handleForceChange = useCallback((force: boolean): void => {
    hasLocalPreferenceChangesRef.current = true;
    telemetry.recordCounter('tier_force_toggle_count');
    setTierForce(force);
    setTurnMetadata(EMPTY_TURN_METADATA);
    enqueuePreferencesUpdate({ tierForce: force });
  }, [enqueuePreferencesUpdate, setTurnMetadata, telemetry]);

  const handleToggleContext = useCallback((): void => {
    if (window.innerWidth > 992) {
      togglePanel();
      return;
    }
    openMobileDrawer();
  }, [openMobileDrawer, togglePanel]);

  const messages = useMemo(() => sessionState.messages, [sessionState.messages]);

  return (
    <div className="chat-page-layout">
      <div className="chat-container">
        <ChatToolbar
          chatSessionId={chatSessionId}
          connected={isConnected}
          panelOpen={panelOpen}
          onNewChat={startNewConversation}
          onToggleContext={handleToggleContext}
        />

        <ChatConversation
          scrollRef={scrollRef}
          historyLoading={sessionState.historyLoading}
          historyError={sessionState.historyError}
          isLoadingEarlier={isLoadingEarlier}
          hasMoreHistory={sessionState.hasMoreHistory}
          messages={messages}
          typing={sessionState.typing}
          progress={sessionState.progress}
          modelsConfig={modelsConfig}
          onScroll={handleScroll}
          onRetryHistory={reloadHistory}
          onLoadEarlierMessages={handleLoadEarlierMessages}
          onRetryMessage={handleRetry}
          onStarterPromptSelect={(text) => handleSend({ text, attachments: [] })}
        />

        <ChatInput
          onSend={handleSend}
          running={running}
          onStop={() => {
            stopSession(chatSessionId, clientInstanceId);
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
