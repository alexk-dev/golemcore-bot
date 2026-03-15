import { describe, expect, it } from 'vitest';

import type { PluginMarketplaceItem } from '../../api/plugins';
import {
  compatibilityLabel,
  compatibilityVariant,
  installLabel,
  isPendingForItem,
  matchesFilter,
  matchesSearch,
  resolvePendingState,
  uninstallConfirmationMessage,
  uninstallLabel,
} from './pluginMarketplaceUi';

function marketplaceItem(overrides: Partial<PluginMarketplaceItem> = {}): PluginMarketplaceItem {
  return {
    id: 'golemcore/browser',
    provider: 'golemcore',
    name: 'Browser',
    description: 'Browser automation',
    version: '1.2.0',
    pluginApiVersion: 1,
    engineVersion: '>=0.12.0',
    sourceUrl: 'https://example.test/browser',
    license: 'MIT',
    maintainers: ['GolemCore'],
    official: true,
    compatible: true,
    artifactAvailable: true,
    installed: false,
    loaded: false,
    updateAvailable: false,
    installedVersion: null,
    loadedVersion: null,
    settingsRouteKey: null,
    ...overrides,
  };
}

describe('pluginMarketplaceUi', () => {
  it('shouldResolvePendingStateForInstallAndUninstall', () => {
    expect(resolvePendingState(true, 'golemcore/browser', false, null)).toEqual({
      action: 'install',
      pluginId: 'golemcore/browser',
    });
    expect(resolvePendingState(false, null, true, 'golemcore/browser')).toEqual({
      action: 'uninstall',
      pluginId: 'golemcore/browser',
    });
    expect(resolvePendingState(false, null, false, null)).toEqual({
      action: null,
      pluginId: null,
    });
  });

  it('shouldBuildUninstallConfirmationMessageBasedOnRuntimeState', () => {
    expect(uninstallConfirmationMessage(null)).toBe('');
    expect(uninstallConfirmationMessage(marketplaceItem({ loaded: false })))
      .toContain('deletes its installed plugin files.');
    expect(uninstallConfirmationMessage(marketplaceItem({ loaded: true })))
      .toContain('unloads it immediately.');
  });

  it('shouldMatchSearchAndFilterModes', () => {
    const item = marketplaceItem({ installed: true, updateAvailable: true });

    expect(matchesFilter(item, 'all')).toBe(true);
    expect(matchesFilter(item, 'installed')).toBe(true);
    expect(matchesFilter(item, 'updates')).toBe(true);
    expect(matchesSearch(item, 'browser')).toBe(true);
    expect(matchesSearch(item, 'golemcore')).toBe(true);
    expect(matchesSearch(item, 'missing')).toBe(false);
  });

  it('shouldDescribeInstallAndUninstallActionsForPendingAndLoadedStates', () => {
    const installPendingItem = marketplaceItem({ updateAvailable: true });
    const uninstallLoadedItem = marketplaceItem({ installed: true, loaded: true });
    const uninstallPendingItem = marketplaceItem({ installed: true });

    expect(installLabel(installPendingItem, 'install', 'golemcore/browser')).toBe('Updating...');
    expect(uninstallLabel(uninstallLoadedItem, null, null)).toBe('Unload & uninstall');
    expect(uninstallLabel(uninstallPendingItem, 'uninstall', 'golemcore/browser')).toBe('Uninstalling...');
    expect(isPendingForItem(uninstallPendingItem, 'uninstall', 'golemcore/browser', 'uninstall')).toBe(true);
  });

  it('shouldDescribeCompatibilityBadgeState', () => {
    expect(compatibilityLabel(marketplaceItem({ compatible: false }))).toBe('Incompatible');
    expect(compatibilityVariant(marketplaceItem({ compatible: false }))).toBe('danger');
    expect(compatibilityLabel(marketplaceItem({ artifactAvailable: false }))).toBe('Artifact missing');
    expect(compatibilityLabel(marketplaceItem({ updateAvailable: true }))).toBe('Update available');
    expect(compatibilityLabel(marketplaceItem({ loaded: true }))).toBe('Loaded');
    expect(compatibilityVariant(marketplaceItem({ loaded: true }))).toBe('success');
  });
});
