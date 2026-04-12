import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { HiveStatusResponse } from '../../api/hive';
import type { HiveConfig } from '../../api/settings';
import HiveTab from './HiveTab';

const hiveStatus = vi.hoisted(() => ({
  value: undefined as HiveStatusResponse | undefined,
}));

vi.mock('../../hooks/useSettings', () => ({
  useUpdateHive: () => ({ isPending: false, mutateAsync: vi.fn(async () => {}) }),
}));

vi.mock('../../hooks/useHive', () => ({
  useHiveStatus: () => ({ data: hiveStatus.value }),
  useJoinHive: () => ({ isPending: false, mutateAsync: vi.fn(() => Promise.resolve(hiveStatus.value)) }),
  useReconnectHive: () => ({ isPending: false, mutateAsync: vi.fn(() => Promise.resolve(hiveStatus.value)) }),
  useLeaveHive: () => ({ isPending: false, mutateAsync: vi.fn(() => Promise.resolve(hiveStatus.value)) }),
}));

const config: HiveConfig = {
  enabled: true,
  managedByProperties: false,
  serverUrl: 'https://hive.example.com',
  displayName: 'Build Runner',
  hostLabel: 'builder-a',
  autoConnect: true,
};

describe('HiveTab', () => {
  it('shows the active policy group and sync state in the connection summary', () => {
    hiveStatus.value = {
      state: 'CONNECTED',
      enabled: true,
      managedByProperties: false,
      managedJoinCodeAvailable: false,
      autoConnect: true,
      serverUrl: 'https://hive.example.com',
      displayName: 'Build Runner',
      hostLabel: 'builder-a',
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
      policyGroupId: 'policy-prod',
      targetPolicyVersion: 5,
      appliedPolicyVersion: 4,
      policySyncStatus: 'OUT_OF_SYNC',
      lastPolicyErrorDigest: 'catalog checksum mismatch',
    };

    const html = renderToStaticMarkup(
      <HiveTab config={config} />,
    );

    expect(html).toContain('Policy group');
    expect(html).toContain('policy-prod');
    expect(html).toContain('OUT_OF_SYNC');
    expect(html).toContain('Applied v4');
    expect(html).toContain('Target v5');
    expect(html).toContain('Last policy error: catalog checksum mismatch');
  });
});
