/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const recordCounter = vi.hoisted(() => vi.fn());
const recordKeyedCounter = vi.hoisted(() => vi.fn());
const appendOptimisticUserMessage = vi.hoisted(() => vi.fn());
const retryUserMessage = vi.hoisted(() => vi.fn());
const sendMessage = vi.hoisted(() => vi.fn(() => true));
const resetSession = vi.hoisted(() => vi.fn());
const updatePreferences = vi.hoisted(() => vi.fn(() => new Promise(() => {})));

vi.mock('../../lib/telemetry/TelemetryContext', () => ({
  useTelemetry: () => ({
    recordCounter,
    recordKeyedCounter,
    recordCounterByRoute: vi.fn(),
    recordUiError: vi.fn(),
  }),
}));

vi.mock('../../store/chatSessionStore', () => ({
  useChatSessionStore: (selector: (state: Record<string, unknown>) => unknown) => selector({
    activeSessionId: 'session-1',
    openSessionIds: ['session-1'],
    setActiveSessionId: vi.fn(),
    openSession: vi.fn(),
    closeSession: vi.fn(),
    startNewSession: vi.fn(() => 'session-2'),
    clientInstanceId: 'client-1',
  }),
}));

vi.mock('../../store/contextPanelStore', () => ({
  useContextPanelStore: (selector?: (state: Record<string, unknown>) => unknown) => {
    const state = {
      panelOpen: true,
      togglePanel: vi.fn(),
      mobileDrawerOpen: false,
      openMobileDrawer: vi.fn(),
      closeMobileDrawer: vi.fn(),
      setTurnMetadata: vi.fn(),
      setGoals: vi.fn(),
      turnMetadata: {
        model: null,
        tier: null,
        reasoning: null,
        inputTokens: null,
        outputTokens: null,
        totalTokens: null,
        latencyMs: null,
        maxContextTokens: null,
        fileChanges: [],
      },
      goals: [],
      goalsFeatureEnabled: false,
    };
    return typeof selector === 'function' ? selector(state) : state;
  },
}));

vi.mock('../../store/chatRuntimeStore', () => ({
  useChatRuntimeStore: (selector: (state: Record<string, unknown>) => unknown) => selector({
    connectionState: 'connected',
    appendOptimisticUserMessage,
    retryUserMessage,
    sendMessage,
    stopSession: vi.fn(),
    resetSession,
  }),
}));

vi.mock('../../hooks/useSessions', () => ({
  useCreateSession: () => ({
    mutate: vi.fn(),
  }),
}));

vi.mock('../../hooks/useModels', () => ({
  useModelsConfig: () => ({
    data: null,
  }),
}));

vi.mock('../../api/goals', () => ({
  getGoals: () => new Promise(() => {}),
}));

vi.mock('../../api/settings', () => ({
  getSettings: () => new Promise(() => {}),
  updatePreferences,
}));

vi.mock('./useChatSessionHistory', () => ({
  useChatSessionHistory: () => ({
    sessionState: {
      historyLoading: false,
      messages: [],
      running: false,
      typing: false,
      hasMoreHistory: false,
    },
    loadEarlierMessages: vi.fn(() => Promise.resolve(false)),
    reloadHistory: vi.fn(),
  }),
}));

vi.mock('./ChatToolbar', () => ({
  ChatToolbar: () => <div>toolbar</div>,
}));

vi.mock('./ChatConversation', () => ({
  ChatConversation: () => <div>conversation</div>,
}));

vi.mock('./ChatInput', () => ({
  default: ({
    onSend,
  }: {
    onSend: (payload: { text: string; attachments: never[] }) => void;
  }) => (
    <button type="button" onClick={() => onSend({ text: 'hello', attachments: [] })}>
      Send message
    </button>
  ),
}));

vi.mock('./ContextPanel', () => ({
  default: ({
    onForceChange,
    onTierChange,
  }: {
    onForceChange: (value: boolean) => void;
    onTierChange: (value: string) => void;
  }) => (
    <div>
      <button type="button" onClick={() => onTierChange('deep')}>Change tier</button>
      <button type="button" onClick={() => onForceChange(true)}>Force tier</button>
    </div>
  ),
}));

import ChatWindow from './ChatWindow';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

function getButtonByText(label: string): HTMLButtonElement {
  const button = Array.from(document.querySelectorAll('button')).find(
    (element): element is HTMLButtonElement => element.textContent === label,
  );
  if (button == null) {
    throw new Error(`Button "${label}" not found`);
  }
  return button;
}

describe('ChatWindow telemetry', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  beforeEach(() => {
    recordCounter.mockClear();
    recordKeyedCounter.mockClear();
    appendOptimisticUserMessage.mockClear();
    sendMessage.mockClear();
    updatePreferences.mockClear();
  });

  it('records chat send and force-tier toggles', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(<ChatWindow />);
    });

    const sendButton = getButtonByText('Send message');
    const forceButton = getButtonByText('Force tier');
    const changeTierButton = getButtonByText('Change tier');

    act(() => {
      sendButton.click();
      forceButton.click();
      changeTierButton.click();
    });

    expect(recordCounter).toHaveBeenCalledWith('chat_send_count');
    expect(recordCounter).toHaveBeenCalledWith('tier_force_toggle_count');
    expect(recordKeyedCounter).toHaveBeenCalledWith('tier_select_count_by_tier', 'deep');

    act(() => {
      root.unmount();
    });
  });
});
