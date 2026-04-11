import { FiPackage } from 'react-icons/fi';

import type { PluginMarketplaceCatalogResponse, PluginSettingsCatalogItem } from '../../api/plugins';
import { SETTINGS_BLOCKS, SETTINGS_SECTIONS, type SettingsSectionMeta } from './settingsCatalog';
import type { CatalogBlockView } from './SettingsCatalogView';

export interface CatalogBadgeMeta {
  label: string;
  variant: string;
  meta: string;
}

export function buildMarketplaceBadge(pluginMarketplace: PluginMarketplaceCatalogResponse | undefined): CatalogBadgeMeta | null {
  if (pluginMarketplace == null) {
    return null;
  }
  const installedCount = pluginMarketplace.items.filter((item) => item.installed).length;
  const updatesCount = pluginMarketplace.items.filter((item) => item.updateAvailable).length;
  const installedMeta = `${installedCount} installed plugin${installedCount === 1 ? '' : 's'}`;
  if (!pluginMarketplace.available) {
    return { label: 'Unavailable', variant: 'secondary', meta: pluginMarketplace.message ?? 'Marketplace metadata is not available.' };
  }
  if (updatesCount > 0) {
    return { label: `${updatesCount} update${updatesCount === 1 ? '' : 's'}`, variant: 'warning', meta: installedMeta };
  }
  return { label: `${pluginMarketplace.items.length} plugins`, variant: 'secondary', meta: installedMeta };
}

export function buildCatalogBlocks(
  pluginCatalog: PluginSettingsCatalogItem[],
  marketplaceBadge: CatalogBadgeMeta | null,
): CatalogBlockView[] {
  const byKey = new Map<string, CatalogBlockView>();
  SETTINGS_BLOCKS.forEach((block) => {
    byKey.set(block.key, {
      key: block.key,
      title: block.title,
      description: block.description,
      items: block.sections.flatMap((sectionKey) => {
        const entry = SETTINGS_SECTIONS.find((candidate) => candidate.key === sectionKey);
        return entry == null ? [] : [{
          key: entry.key,
          routeKey: entry.key,
          title: entry.title,
          description: entry.description,
          icon: entry.icon,
          badgeLabel: entry.key === 'plugins-marketplace' ? marketplaceBadge?.label : undefined,
          badgeVariant: entry.key === 'plugins-marketplace' ? marketplaceBadge?.variant : undefined,
          metaText: entry.key === 'plugins-marketplace' ? marketplaceBadge?.meta : undefined,
        }];
      }),
    });
  });
  appendPluginCatalogBlocks(byKey, pluginCatalog);
  return Array.from(byKey.values()).filter((block) => block.items.length > 0);
}

function appendPluginCatalogBlocks(byKey: Map<string, CatalogBlockView>, pluginCatalog: PluginSettingsCatalogItem[]): void {
  pluginCatalog
    .slice()
    .sort((left, right) => (left.order ?? Number.MAX_SAFE_INTEGER) - (right.order ?? Number.MAX_SAFE_INTEGER) || left.title.localeCompare(right.title))
    .forEach((item) => {
      const blockKey = item.blockKey ?? 'plugins';
      const current = byKey.get(blockKey) ?? { key: blockKey, title: item.blockTitle ?? 'Plugins', description: item.blockDescription ?? 'Plugin-provided settings', items: [] };
      current.items.push({ key: item.routeKey, routeKey: item.routeKey, title: item.title, description: item.description, icon: FiPackage });
      byKey.set(blockKey, current);
    });
}

export function resolveSectionMeta(
  staticSection: string | null,
  pluginSection: PluginSettingsCatalogItem | null,
): (Pick<SettingsSectionMeta, 'title' | 'description' | 'icon'> & { key: string }) | null {
  if (staticSection != null) {
    return SETTINGS_SECTIONS.find((entry) => entry.key === staticSection) ?? null;
  }
  return pluginSection == null ? null : { key: pluginSection.routeKey, title: pluginSection.title, description: pluginSection.description, icon: FiPackage };
}
