import type { SkillMarketplaceCatalogResponse, SkillMarketplaceItem } from '../../api/skills';
import type { SkillsConfig } from '../../api/settings';

export const DEFAULT_SKILLS_REPOSITORY = 'https://github.com/alexk-dev/golemcore-skills';

export type MarketplaceFilter = 'all' | 'installed' | 'updates';
export type MarketplaceSourceType = 'repository' | 'directory' | 'sandbox';
export type SourceBadgeVariant = 'info' | 'secondary';

export interface MarketplaceSourceForm {
  marketplaceSourceType: MarketplaceSourceType;
  marketplaceRepositoryDirectory: string;
  marketplaceSandboxPath: string;
  marketplaceRepositoryUrl: string;
  marketplaceBranch: string;
}

export interface MarketplaceStats {
  installedCount: number;
  updatesCount: number;
  packCount: number;
}

export function matchesFilter(item: SkillMarketplaceItem, filter: MarketplaceFilter): boolean {
  if (filter === 'installed') {
    return item.installed;
  }
  if (filter === 'updates') {
    return item.updateAvailable;
  }
  return true;
}

export function matchesSearch(item: SkillMarketplaceItem, query: string): boolean {
  if (query.length === 0) {
    return true;
  }
  return item.name.toLowerCase().includes(query)
    || item.id.toLowerCase().includes(query)
    || (item.description ?? '').toLowerCase().includes(query)
    || (item.maintainer ?? '').toLowerCase().includes(query);
}

export function sourceTypeLabel(sourceType: MarketplaceSourceType | null | undefined): string {
  if (sourceType === 'directory') {
    return 'Local path';
  }
  if (sourceType === 'sandbox') {
    return 'Sandbox path';
  }
  return 'Repository';
}

export function sourceTypeBadgeVariant(sourceType: MarketplaceSourceType | null | undefined): SourceBadgeVariant {
  return sourceType === 'repository' ? 'secondary' : 'info';
}

export function isPathSourceType(sourceType: MarketplaceSourceType): boolean {
  return sourceType === 'directory' || sourceType === 'sandbox';
}

export function pathInputLabel(sourceType: MarketplaceSourceType): string {
  return sourceType === 'sandbox' ? 'Sandbox path' : 'Local path';
}

export function pathInputPlaceholder(sourceType: MarketplaceSourceType): string {
  return sourceType === 'sandbox'
    ? 'repos/golemcore-skills'
    : '/absolute/path/to/golemcore-skills';
}

export function normalizeSourceForm(sourceForm: MarketplaceSourceForm): MarketplaceSourceForm {
  return {
    ...sourceForm,
    marketplaceRepositoryDirectory: sourceForm.marketplaceRepositoryDirectory.trim(),
    marketplaceSandboxPath: sourceForm.marketplaceSandboxPath.trim(),
    marketplaceRepositoryUrl: sourceForm.marketplaceRepositoryUrl.trim(),
    marketplaceBranch: sourceForm.marketplaceBranch.trim(),
  };
}

export function buildMarketplaceStats(items: SkillMarketplaceItem[]): MarketplaceStats {
  return {
    installedCount: items.filter((item) => item.installed).length,
    updatesCount: items.filter((item) => item.updateAvailable).length,
    packCount: items.filter((item) => item.artifactType === 'pack').length,
  };
}

export function buildSourceForm(
  config: SkillsConfig | undefined,
  catalog: SkillMarketplaceCatalogResponse | undefined,
): MarketplaceSourceForm {
  const effectiveSourceType = resolveSourceType(config, catalog);

  return {
    marketplaceSourceType: effectiveSourceType,
    marketplaceRepositoryDirectory: resolveDirectorySource(config, catalog),
    marketplaceSandboxPath: resolveSandboxSource(config, catalog),
    marketplaceRepositoryUrl: resolveRepositorySource(config, catalog),
    marketplaceBranch: config?.marketplaceBranch ?? 'main',
  };
}

export function sourceSummaryLabel(catalog: SkillMarketplaceCatalogResponse | undefined): string {
  const sourceDirectory = catalog?.sourceDirectory ?? '';
  if (sourceDirectory.length === 0) {
    return 'Marketplace source has not been resolved yet.';
  }
  if (catalog?.sourceType === 'directory') {
    return `Resolved local path: ${sourceDirectory}`;
  }
  if (catalog?.sourceType === 'sandbox') {
    return `Resolved sandbox path: ${sourceDirectory}`;
  }
  return `Resolved repository: ${sourceDirectory}`;
}

function resolveSourceType(
  config: SkillsConfig | undefined,
  catalog: SkillMarketplaceCatalogResponse | undefined,
): MarketplaceSourceType {
  const effectiveSourceType = config?.marketplaceSourceType ?? catalog?.sourceType;
  return effectiveSourceType === 'directory' || effectiveSourceType === 'sandbox'
    ? effectiveSourceType
    : 'repository';
}

function resolveDirectorySource(
  config: SkillsConfig | undefined,
  catalog: SkillMarketplaceCatalogResponse | undefined,
): string {
  if (config?.marketplaceRepositoryDirectory != null) {
    return config.marketplaceRepositoryDirectory;
  }
  return catalog?.sourceType === 'directory' ? catalog.sourceDirectory ?? '' : '';
}

function resolveSandboxSource(
  config: SkillsConfig | undefined,
  catalog: SkillMarketplaceCatalogResponse | undefined,
): string {
  if (config?.marketplaceSandboxPath != null) {
    return config.marketplaceSandboxPath;
  }
  return catalog?.sourceType === 'sandbox' ? catalog.sourceDirectory ?? '' : '';
}

function resolveRepositorySource(
  config: SkillsConfig | undefined,
  catalog: SkillMarketplaceCatalogResponse | undefined,
): string {
  if (config?.marketplaceRepositoryUrl != null) {
    return config.marketplaceRepositoryUrl;
  }
  if (catalog?.sourceType === 'repository') {
    return catalog.sourceDirectory ?? '';
  }
  return DEFAULT_SKILLS_REPOSITORY;
}
