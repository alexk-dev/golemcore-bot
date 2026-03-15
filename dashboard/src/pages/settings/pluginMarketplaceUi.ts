import type { PluginMarketplaceItem } from '../../api/plugins';

export type MarketplacePendingAction = 'install' | 'uninstall' | null;

export interface MarketplacePendingState {
  action: MarketplacePendingAction;
  pluginId: string | null;
}

export function matchesFilter(item: PluginMarketplaceItem, filter: 'all' | 'installed' | 'updates'): boolean {
  if (filter === 'installed') {
    return item.installed;
  }
  if (filter === 'updates') {
    return item.updateAvailable;
  }
  return true;
}

export function matchesSearch(item: PluginMarketplaceItem, query: string): boolean {
  if (query.length === 0) {
    return true;
  }
  return item.name.toLowerCase().includes(query)
    || item.id.toLowerCase().includes(query)
    || item.provider.toLowerCase().includes(query);
}

export function resolvePendingState(
  installPending: boolean,
  installPluginId: string | null,
  uninstallPending: boolean,
  uninstallPluginId: string | null,
): MarketplacePendingState {
  if (installPending) {
    return {
      action: 'install',
      pluginId: installPluginId,
    };
  }
  if (uninstallPending) {
    return {
      action: 'uninstall',
      pluginId: uninstallPluginId,
    };
  }
  return {
    action: null,
    pluginId: null,
  };
}

export function uninstallConfirmationMessage(item: PluginMarketplaceItem | null): string {
  if (item == null) {
    return '';
  }
  if (item.loaded) {
    return `Remove ${item.name} from this runtime? This deletes its installed plugin files and unloads it immediately.`;
  }
  return `Remove ${item.name} from this runtime? This deletes its installed plugin files.`;
}

export function isPendingForItem(
  item: PluginMarketplaceItem,
  pendingAction: MarketplacePendingAction,
  pendingPluginId: string | null,
  action: Exclude<MarketplacePendingAction, null>,
): boolean {
  return pendingAction === action && pendingPluginId === item.id;
}

export function installLabel(
  item: PluginMarketplaceItem,
  pendingAction: MarketplacePendingAction,
  pendingPluginId: string | null,
): string {
  if (isPendingForItem(item, pendingAction, pendingPluginId, 'install')) {
    return item.updateAvailable ? 'Updating...' : 'Installing...';
  }
  if (item.updateAvailable) {
    return `Update to ${item.version}`;
  }
  if (item.installed) {
    return 'Installed';
  }
  return 'Install';
}

export function installVariant(item: PluginMarketplaceItem): string {
  if (item.installed && !item.updateAvailable) {
    return 'secondary';
  }
  return 'primary';
}

export function uninstallLabel(
  item: PluginMarketplaceItem,
  pendingAction: MarketplacePendingAction,
  pendingPluginId: string | null,
): string {
  if (isPendingForItem(item, pendingAction, pendingPluginId, 'uninstall')) {
    return 'Uninstalling...';
  }
  return item.loaded ? 'Unload & uninstall' : 'Uninstall';
}

export function compatibilityLabel(item: PluginMarketplaceItem): string {
  if (!item.compatible) {
    return 'Incompatible';
  }
  if (!item.artifactAvailable) {
    return 'Artifact missing';
  }
  if (item.updateAvailable) {
    return 'Update available';
  }
  if (item.loaded) {
    return 'Loaded';
  }
  if (item.installed) {
    return 'Installed';
  }
  return 'Available';
}

export function compatibilityVariant(item: PluginMarketplaceItem): string {
  if (!item.compatible || !item.artifactAvailable) {
    return 'danger';
  }
  if (item.updateAvailable) {
    return 'warning';
  }
  if (item.loaded) {
    return 'success';
  }
  return 'secondary';
}
