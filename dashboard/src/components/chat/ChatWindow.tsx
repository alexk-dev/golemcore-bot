import { useCallback, useEffect, useMemo, useRef, useState, type ReactElement } from 'react';
import { Offcanvas } from '../ui/tailwind-components';
import { useChatSessionStore } from '../../store/chatSessionStore';
import { useChatUiStore } from '../../store/chatUiStore';
import { useContextPanelStore } from '../../store/contextPanelStore';
import { useChatRuntimeStore } from '../../store/chatRuntimeStore';
import { useCreateSession } from '../../hooks/useSessions';
import { useModelsConfig } from '../../hooks/useModels';
import { getGoals } from '../../api/goals';
import { getMemoryPresets, getSettings, updatePreferences, type MemoryPreset } from '../../api/settings';
import { createUuid } from '../../utils/uuid';
import ChatInput from './ChatInput';
import ContextPanel from './ContextPanel';
import { ChatConversation } from './ChatConversation';
import { ChatSessionTabs } from './ChatSessionTabs';
import { useChatSessionHotkeys } from './useChatSessionHotkeys';
import { ChatToolbar } from './ChatToolbar';
import type { OutboundChatPayload } from './chatInputTypes';
import { parseRunCommand, routeRunCommandToTerminal } from './chatRunCommand';
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

function normalizeMemoryPreset(value: string | null | undefined): string {
  return value == null ? '' : value.trim().toLowerCase();
}

function patchHasKey(patch: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(patch, key);
}

function findMemoryPresetLabel(presets: MemoryPreset[], presetId: string): string | null {
  if (presetId.length === 0) {
    return null;
  }
  return presets.find((preset) => preset.id === presetId)?.label ?? presetId;
}

export interface ChatWindowProps {
  embedded?: boolean;
}

export default function ChatWindow({ embedded = false }: ChatWindowProps = {}): ReactElement {
  const chatSessionId = useChatSessionStore((state) => state.activeSessionId);
  const startNewChatSession = useChatSessionStore((state) => state.startNewSession);
  const clientInstanceId = useChatSessionStore((state) => state.clientInstanceId);
  const memoryPresetOverride = useChatSessionStore((state) => state.memoryPresetOverrides[chatSessionId] ?? '');
  const setMemoryPresetOverride = useChatSessionStore((state) => state.setMemoryPresetOverride);
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
  const composerCollapsed = useChatUiStore((state) => state.composerCollapsed);
  const toggleComposerCollapsed = useChatUiStore((state) => state.toggleComposerCollapsed);
  const telemetry = useTelemetry();
  useChatSessionHotkeys();
  const [tier, setTier] = useState('balanced');
  const [tierForce, setTierForce] = useState(false);
  const [globalMemoryPreset, setGlobalMemoryPreset] = useState('');
  const [memoryPresets, setMemoryPresets] = useState<MemoryPreset[]>([]);
  const [memoryPresetsLoading, setMemoryPresetsLoading] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);
  const shouldAutoScrollRef = useRef(true);
  const prependScrollHeightRef = useRef<number | null>(null);
  const preferenceUpdateQueueRef = useRef<Promise<void>>(Promise.resolve());
  const hasLocalTierChangeRef = useRef(false);
  const hasLocalTierForceChangeRef = useRef(false);

  const isConnected = connectionState === 'connected';
  const isLoadingEarlier = sessionState.historyLoading && sessionState.messages.length > 0;
  const running = sessionState.running || sessionState.typing;

  useEffect(() => {
    // Keep tier controls in sync with persisted user preferences used by backend tier resolution.
    let cancelled = false;
    getSettings()
      .then((settings) => {
        if (cancelled) {
          return;
        }
        if (!hasLocalTierChangeRef.current) {
          setTier(normalizeTier(settings.modelTier));
        }
        if (!hasLocalTierForceChangeRef.current) {
          setTierForce(settings.tierForce === true);
        }
        setGlobalMemoryPreset(normalizeMemoryPreset(settings.memoryPreset));
      })
      .catch(() => {
        // Ignore settings bootstrap failures; chat can still operate with defaults.
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    // Load memory preset labels for the compact workspace chat selector.
    let cancelled = false;
    setMemoryPresetsLoading(true);
    getMemoryPresets()
      .then((presets) => {
        if (!cancelled) {
          setMemoryPresets(presets);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setMemoryPresets([]);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setMemoryPresetsLoading(false);
        }
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
        if (patchHasKey(prefsPatch, 'modelTier')) {
          setTier(normalizeTier(settings.modelTier));
        }
        if (patchHasKey(prefsPatch, 'tierForce')) {
          setTierForce(settings.tierForce === true);
        }
      })
      .catch(() => {
        // Ignore preference update failures; the optimistic UI state remains local.
      });
  }, []);

  const startNewConversation = useCallback((): void => {
    const newSessionId = startNewChatSession();
    resetSession(newSessionId);
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
  }, [clientInstanceId, createSessionMutation, resetSession, startNewChatSession]);

  const handleSend = useCallback((payload: OutboundChatPayload): void => {
    const trimmed = payload.text.trim();
    if (payload.attachments.length === 0) {
      const runCommand = parseRunCommand(trimmed);
      if (runCommand !== null) {
        routeRunCommandToTerminal(runCommand);
        return;
      }
    }
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
      // Null explicitly means "inherit the global memory preset"; only a
      // session-scoped override is sent over the websocket.
      memoryPreset: memoryPresetOverride.length > 0 ? memoryPresetOverride : null,
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
  }, [appendOptimisticUserMessage, chatSessionId, clientInstanceId, memoryPresetOverride, resetSession, sendMessage, startNewConversation, telemetry]);

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
    hasLocalTierChangeRef.current = true;
    telemetry.recordKeyedCounter('tier_select_count_by_tier', normalizedTier);
    setTier(normalizedTier);
    setTurnMetadata(EMPTY_TURN_METADATA);
    enqueuePreferencesUpdate({ modelTier: normalizedTier });
  }, [enqueuePreferencesUpdate, setTurnMetadata, telemetry]);

  const handleForceChange = useCallback((force: boolean): void => {
    hasLocalTierForceChangeRef.current = true;
    telemetry.recordCounter('tier_force_toggle_count');
    setTierForce(force);
    setTurnMetadata(EMPTY_TURN_METADATA);
    enqueuePreferencesUpdate({ tierForce: force });
  }, [enqueuePreferencesUpdate, setTurnMetadata, telemetry]);

  const handleMemoryPresetChange = useCallback((newPreset: string): void => {
    const normalizedPreset = normalizeMemoryPreset(newPreset);
    telemetry.recordKeyedCounter(
      'memory_preset_select_count_by_preset',
      normalizedPreset.length > 0 ? normalizedPreset : 'default',
    );
    setMemoryPresetOverride(chatSessionId, normalizedPreset);
    setTurnMetadata(EMPTY_TURN_METADATA);
  }, [chatSessionId, setMemoryPresetOverride, setTurnMetadata, telemetry]);

  const handleToggleContext = useCallback((): void => {
    if (window.innerWidth > 992) {
      togglePanel();
      return;
    }
    openMobileDrawer();
  }, [openMobileDrawer, togglePanel]);

  const messages = useMemo(() => sessionState.messages, [sessionState.messages]);
  const inheritedMemoryPresetLabel = useMemo(
    () => findMemoryPresetLabel(memoryPresets, globalMemoryPreset),
    [globalMemoryPreset, memoryPresets],
  );

  const layoutClassName = [
    'chat-page-layout',
    composerCollapsed ? 'chat-page-layout--composer-collapsed' : '',
    embedded ? 'chat-page-layout--embedded' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={layoutClassName}>
      <div className="chat-container">
        <ChatSessionTabs />
        <ChatToolbar
          chatSessionId={chatSessionId}
          connected={isConnected}
          panelOpen={panelOpen}
          onNewChat={startNewConversation}
          onToggleContext={handleToggleContext}
          embedded={embedded}
          tier={tier}
          tierForce={tierForce}
          memoryPreset={memoryPresetOverride}
          memoryPresetOptions={memoryPresets}
          memoryPresetsLoading={memoryPresetsLoading}
          inheritedMemoryPresetLabel={inheritedMemoryPresetLabel}
          onTierChange={handleTierChange}
          onForceChange={handleForceChange}
          onMemoryPresetChange={handleMemoryPresetChange}
        />

        <div id="chat-workspace-body" className="chat-workspace-body">
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
            composerCollapsed={composerCollapsed}
            onToggleComposerCollapsed={toggleComposerCollapsed}
          />
        </div>
      </div>

      {embedded ? null : (
        <ContextPanel
          tier={tier}
          tierForce={tierForce}
          chatSessionId={chatSessionId}
          onTierChange={handleTierChange}
          onForceChange={handleForceChange}
        />
      )}

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
