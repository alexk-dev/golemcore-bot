/* @vitest-environment jsdom */

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { HiveStatusResponse } from '../../api/hive';
import { HiveActionButtons } from './HiveActionButtons';

function connectedStatus(): HiveStatusResponse {
  return {
    state: 'CONNECTED',
    enabled: true,
    managedByProperties: false,
    managedJoinCodeAvailable: false,
    autoConnect: true,
    serverUrl: 'https://hive.example.com',
    displayName: 'Build Runner',
    hostLabel: 'builder-a',
    dashboardBaseUrl: 'https://bot.example.com/dashboard',
    ssoEnabled: true,
    sessionPresent: true,
    golemId: 'golem-1',
    controlChannelUrl: 'wss://hive.example.com/ws',
    heartbeatIntervalSeconds: 30,
    lastConnectedAt: null,
    lastHeartbeatAt: null,
    lastTokenRotatedAt: null,
    controlChannelState: 'CONNECTED',
    controlChannelConnectedAt: null,
    controlChannelLastMessageAt: null,
    controlChannelLastError: null,
    lastReceivedCommandId: null,
    lastReceivedCommandAt: null,
    receivedCommandCount: 0,
    bufferedCommandCount: 0,
    pendingCommandCount: 0,
    pendingEventBatchCount: 0,
    pendingEventCount: 0,
    outboxLastError: null,
    lastError: null,
    policyGroupId: null,
    targetPolicyVersion: null,
    appliedPolicyVersion: null,
    policySyncStatus: null,
    lastPolicyErrorDigest: null,
  };
}

function renderButtons(joinCode: string): HTMLButtonElement[] {
  document.body.innerHTML = renderToStaticMarkup(
    <HiveActionButtons
      isManaged={false}
      isBusy={false}
      joinCode={joinCode}
      status={connectedStatus()}
      joinPending={false}
      reconnectPending={false}
      leavePending={false}
      onJoin={vi.fn()}
      onReconnect={vi.fn()}
      onLeave={vi.fn()}
    />,
  );
  return Array.from(document.body.querySelectorAll('button'));
}

describe('HiveActionButtons', () => {
  it('allows replacing an existing session when a new manual join code is entered', () => {
    const [joinButton] = renderButtons('token-id.secret:https://hive.example.com/');

    expect(joinButton.disabled).toBe(false);
  });

  it('keeps manual join disabled when no replacement join code is entered', () => {
    const [joinButton] = renderButtons('');

    expect(joinButton.disabled).toBe(true);
  });
});
