import { describe, expect, it } from 'vitest';

import { toBackendRuntimeConfig, toUiRuntimeConfig, type RuntimeConfigUiRecord } from './settingsRuntimeMappers';
import { buildRuntimeConfigFixture } from './settingsSelfEvolvingFixtures.testUtils';

describe('settings runtime Hive mapper', () => {
  it('defaults missing Hive SDLC toggles to enabled', () => {
    const runtime = toUiRuntimeConfig(buildRuntimeConfigFixture({ enabled: false }) as RuntimeConfigUiRecord);

    expect(runtime.hive.sdlc.currentContextEnabled).toBe(true);
    expect(runtime.hive.sdlc.cardReadEnabled).toBe(true);
    expect(runtime.hive.sdlc.cardSearchEnabled).toBe(true);
    expect(runtime.hive.sdlc.threadMessageEnabled).toBe(true);
    expect(runtime.hive.sdlc.reviewRequestEnabled).toBe(true);
    expect(runtime.hive.sdlc.followupCardCreateEnabled).toBe(true);
    expect(runtime.hive.sdlc.lifecycleSignalEnabled).toBe(true);
  });

  it('preserves disabled Hive SDLC toggles when saving runtime config', () => {
    const runtime = toUiRuntimeConfig({
      ...buildRuntimeConfigFixture({ enabled: false }),
      hive: {
        enabled: true,
        serverUrl: 'https://hive.example.com',
        displayName: 'Builder',
        hostLabel: 'lab-a',
        autoConnect: true,
        managedByProperties: false,
        sdlc: {
          currentContextEnabled: false,
          cardReadEnabled: true,
          cardSearchEnabled: false,
          threadMessageEnabled: true,
          reviewRequestEnabled: false,
          followupCardCreateEnabled: true,
          lifecycleSignalEnabled: false,
        },
      },
    } as RuntimeConfigUiRecord);

    const payload = toBackendRuntimeConfig(runtime);
    const hive = payload.hive as RuntimeConfigUiRecord;
    const sdlc = hive.sdlc as RuntimeConfigUiRecord;

    expect(sdlc.currentContextEnabled).toBe(false);
    expect(sdlc.cardSearchEnabled).toBe(false);
    expect(sdlc.reviewRequestEnabled).toBe(false);
    expect(sdlc.lifecycleSignalEnabled).toBe(false);
  });
});
