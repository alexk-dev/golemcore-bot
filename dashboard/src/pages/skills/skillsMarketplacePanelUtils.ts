import type { SkillMarketplaceCatalogResponse, SkillMarketplaceItem } from '../../api/skills';
import type { SkillsConfig } from '../../api/settings';

export const DEFAULT_SKILLS_REPOSITORY = 'https://github.com/alexk-dev/golemcore-skills';

export type MarketplaceFilter = 'all' | 'installed' | 'updates';
export type MarketplaceSourceType = 'repository' | 'directory' | 'sandbox';

export interface MarketplaceSourceForm {
  marketplaceSourceType: MarketplaceSourceType;
  marketplaceRepositoryDirectory: string;
  marketplaceSandboxPath: string;
  marketplaceRepositoryUrl: string;
  marketplaceBranch: string;
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

export function buildSourceForm(
  config: SkillsConfig | undefined,
  catalog: SkillMarketplaceCatalogResponse | undefined,
): MarketplaceSourceForm {
  const effectiveSourceType = config?.marketplaceSourceType ?? catalog?.sourceType ?? 'repository';

  return {
    marketplaceSourceType: effectiveSourceType === 'directory' || effectiveSourceType === 'sandbox'
      ? effectiveSourceType
      : 'repository',
    marketplaceRepositoryDirectory: config?.marketplaceRepositoryDirectory
      ?? (catalog?.sourceType === 'directory' ? catalog.sourceDirectory ?? '' : ''),
    marketplaceSandboxPath: config?.marketplaceSandboxPath
      ?? (catalog?.sourceType === 'sandbox' ? catalog.sourceDirectory ?? '' : ''),
    marketplaceRepositoryUrl: config?.marketplaceRepositoryUrl
      ?? (catalog?.sourceType === 'repository' ? catalog.sourceDirectory ?? '' : DEFAULT_SKILLS_REPOSITORY),
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
