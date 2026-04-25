/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const recordCounter = vi.hoisted(() => vi.fn());
const recordKeyedCounter = vi.hoisted(() => vi.fn());
const appendOptimisticUserMessage = vi.hoisted(() => vi.fn());
const retryUserMessage = vi.hoisted(() => vi.fn());
const sendMessage = vi.hoisted(() => vi.fn<(
  sessionId: string,
  clientInstanceId: string,
  clientMessageId: string,
  payload: { memoryPreset?: string | null },
) => boolean>(() => true));
const resetSession = vi.hoisted(() => vi.fn());
const getSettings = vi.hoisted(() => vi.fn<() => Promise<{
  modelTier: string;
  tierForce: boolean;
  memoryPreset: string;
}>>(() => new Promise(() => {})));
const getMemoryPresets = vi.hoisted(() => vi.fn<() => Promise<Array<{
  id: string;
  label: string;
}>>>(() => Promise.resolve([])));
const getGoalsMock = vi.hoisted(() => vi.fn(() => new Promise(() => {})));
const updatePreferences = vi.hoisted(() => vi.fn(() => new Promise(() => {})));
const activeSessionIdRef = vi.hoisted(() => ({ value: 'session-1' }));
const memoryPresetOverridesRef = vi.hoisted(() => ({ value: {} as Record<string, string> }));

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
    activeSessionId: activeSessionIdRef.value,
    openSessionIds: ['session-1', 'session-2'],
    memoryPresetOverrides: memoryPresetOverridesRef.value,
    setActiveSessionId: vi.fn(),
    openSession: vi.fn(),
    closeSession: vi.fn(),
    startNewSession: vi.fn(() => 'session-2'),
    setMemoryPresetOverride: (sessionId: string, preset: string) => {
      if (preset.length === 0) {
        const { [sessionId]: _removed, ...remaining } = memoryPresetOverridesRef.value;
        memoryPresetOverridesRef.value = remaining;
        return;
      }
      memoryPresetOverridesRef.value = { ...memoryPresetOverridesRef.value, [sessionId]: preset };
    },
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
      standaloneTasks: [],
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
  getGoals: getGoalsMock,
}));

vi.mock('../../api/settings', () => ({
  getSettings,
  getMemoryPresets,
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
  ChatToolbar: ({
    inheritedMemoryPresetLabel,
    onMemoryPresetChange,
    onTierChange,
  }: {
    inheritedMemoryPresetLabel?: string | null;
    onMemoryPresetChange?: (value: string) => void;
    onTierChange?: (value: string) => void;
  }) => (
    <div>
      <span data-testid="inherited-memory">{inheritedMemoryPresetLabel ?? ''}</span>
      <button type="button" onClick={() => onTierChange?.('deep')}>Toolbar tier</button>
      <button type="button" onClick={() => onMemoryPresetChange?.('coding_balanced')}>Toolbar memory</button>
    </div>
  ),
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
    getSettings.mockReset();
    getSettings.mockImplementation(() => new Promise(() => {}));
    getMemoryPresets.mockReset();
    getMemoryPresets.mockResolvedValue([]);
    getGoalsMock.mockClear();
    updatePreferences.mockClear();
    activeSessionIdRef.value = 'session-1';
    memoryPresetOverridesRef.value = {};
  });

  it('loads goals for the active chat session', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(<ChatWindow embedded />);
    });

    expect(getGoalsMock).toHaveBeenCalledWith({
      channel: 'web',
      conversationKey: 'session-1',
    });

    act(() => {
      root.unmount();
    });
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

  it('sends a chat-local memory preset override without updating global preferences', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(<ChatWindow embedded />);
    });

    act(() => {
      getButtonByText('Toolbar memory').click();
    });
    act(() => {
      root.render(<ChatWindow embedded />);
    });

    act(() => {
      getButtonByText('Send message').click();
    });

    expect(sendMessage.mock.calls[0]?.[3]).toMatchObject({ memoryPreset: 'coding_balanced' });
    expect(updatePreferences).not.toHaveBeenCalled();

    act(() => {
      root.unmount();
    });
  });

  it('uses the stored chat-local memory preset override after remount', () => {
    memoryPresetOverridesRef.value = { 'session-1': 'coding_balanced' };
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(<ChatWindow embedded />);
    });

    act(() => {
      getButtonByText('Send message').click();
    });

    expect(sendMessage.mock.calls[0]?.[3]).toMatchObject({ memoryPreset: 'coding_balanced' });

    act(() => {
      root.unmount();
    });
  });

  it('scopes memory preset overrides to the active chat session', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(<ChatWindow embedded />);
    });

    act(() => {
      getButtonByText('Toolbar memory').click();
    });

    activeSessionIdRef.value = 'session-2';
    act(() => {
      root.render(<ChatWindow embedded />);
    });

    act(() => {
      getButtonByText('Send message').click();
    });

    expect(sendMessage.mock.calls[0]?.[0]).toBe('session-2');
    expect(sendMessage.mock.calls[0]?.[3]).toMatchObject({ memoryPreset: null });

    act(() => {
      root.unmount();
    });
  });

  it('omits a memory preset override while inheriting global memory settings', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(<ChatWindow embedded />);
    });

    act(() => {
      getButtonByText('Send message').click();
    });

    expect(sendMessage.mock.calls[0]?.[3]).toMatchObject({ memoryPreset: null });

    act(() => {
      root.unmount();
    });
  });

  it('keeps the inherited memory label when local tier changes before settings load', async () => {
    let resolveSettings: (settings: {
      modelTier: string;
      tierForce: boolean;
      memoryPreset: string;
    }) => void = () => {};
    getSettings.mockImplementation(() => new Promise((resolve) => {
      resolveSettings = resolve;
    }));
    getMemoryPresets.mockResolvedValue([
      { id: 'coding_balanced', label: 'Coding balanced' },
    ]);
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(<ChatWindow embedded />);
    });

    act(() => {
      getButtonByText('Toolbar tier').click();
    });

    await act(async () => {
      resolveSettings({
        modelTier: 'smart',
        tierForce: false,
        memoryPreset: 'coding_balanced',
      });
      await Promise.resolve();
    });

    expect(document.querySelector('[data-testid="inherited-memory"]')?.textContent)
      .toBe('Coding balanced');

    act(() => {
      root.unmount();
    });
  });
});
