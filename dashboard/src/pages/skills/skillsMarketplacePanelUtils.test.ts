import { describe, expect, it } from 'vitest';
import type { SkillsConfig } from '../../api/settingsTypes';
import type { SkillMarketplaceCatalogResponse, SkillMarketplaceItem } from '../../api/skills';
import {
  buildSourceForm,
  matchesFilter,
  matchesSearch,
  sourceSummaryLabel,
} from './skillsMarketplacePanelUtils';

function createCatalog(overrides: Partial<SkillMarketplaceCatalogResponse> = {}): SkillMarketplaceCatalogResponse {
  return {
    available: true,
    sourceType: 'repository',
    sourceDirectory: 'https://github.com/alexk-dev/golemcore-skills',
    items: [],
    ...overrides,
  };
}

function createSkillsConfig(overrides: Partial<SkillsConfig> = {}): SkillsConfig {
  return {
    enabled: true,
    progressiveLoading: true,
    marketplaceSourceType: 'repository',
    marketplaceRepositoryDirectory: null,
    marketplaceSandboxPath: null,
    marketplaceRepositoryUrl: 'https://github.com/alexk-dev/golemcore-skills',
    marketplaceBranch: 'main',
    ...overrides,
  };
}

function createItem(overrides: Partial<SkillMarketplaceItem> = {}): SkillMarketplaceItem {
  return {
    id: 'golemcore/devops-pack',
    name: 'DevOps Pack',
    description: 'Delivery and incident response skills.',
    maintainer: 'golemcore',
    maintainerDisplayName: 'Golemcore',
    artifactId: 'devops-pack',
    artifactType: 'pack',
    version: '1.2.0',
    modelTier: null,
    sourcePath: 'registry/golemcore/devops-pack/artifact.yaml',
    skillRefs: ['golemcore/devops-pack/deploy-review'],
    skillCount: 1,
    installed: false,
    updateAvailable: false,
    ...overrides,
  };
}

describe('skills marketplace panel helpers', () => {
  it('builds source form from config first and falls back to repository defaults', () => {
    const sourceForm = buildSourceForm(createSkillsConfig(), undefined);

    expect(sourceForm).toEqual({
      marketplaceSourceType: 'repository',
      marketplaceRepositoryDirectory: '',
      marketplaceSandboxPath: '',
      marketplaceRepositoryUrl: 'https://github.com/alexk-dev/golemcore-skills',
      marketplaceBranch: 'main',
    });
  });

  it('builds source form from catalog when runtime config is not loaded yet', () => {
    const sourceForm = buildSourceForm(undefined, createCatalog({
      sourceType: 'directory',
      sourceDirectory: '/opt/golemcore-skills',
    }));

    expect(sourceForm).toEqual({
      marketplaceSourceType: 'directory',
      marketplaceRepositoryDirectory: '/opt/golemcore-skills',
      marketplaceSandboxPath: '',
      marketplaceRepositoryUrl: 'https://github.com/alexk-dev/golemcore-skills',
      marketplaceBranch: 'main',
    });
  });

  it('summarizes unresolved and resolved marketplace sources', () => {
    expect(sourceSummaryLabel(undefined)).toBe('Marketplace source has not been resolved yet.');
    expect(sourceSummaryLabel(createCatalog({
      sourceType: 'sandbox',
      sourceDirectory: 'repos/golemcore-skills',
    }))).toBe('Resolved sandbox path: repos/golemcore-skills');
    expect(sourceSummaryLabel(createCatalog({
      sourceType: 'directory',
      sourceDirectory: '/opt/golemcore-skills',
    }))).toBe('Resolved local path: /opt/golemcore-skills');
  });

  it('matches marketplace filter states', () => {
    expect(matchesFilter(createItem(), 'all')).toBe(true);
    expect(matchesFilter(createItem({ installed: true }), 'installed')).toBe(true);
    expect(matchesFilter(createItem(), 'installed')).toBe(false);
    expect(matchesFilter(createItem({ updateAvailable: true }), 'updates')).toBe(true);
    expect(matchesFilter(createItem(), 'updates')).toBe(false);
  });

  it('matches search query against name, id, description, and maintainer', () => {
    const item = createItem();

    expect(matchesSearch(item, '')).toBe(true);
    expect(matchesSearch(item, 'devops')).toBe(true);
    expect(matchesSearch(item, 'incident response')).toBe(true);
    expect(matchesSearch(item, 'golemcore')).toBe(true);
    expect(matchesSearch(item, 'does-not-exist')).toBe(false);
  });
});
