import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { SkillsMarketplacePanel } from './SkillsMarketplacePanel';

vi.mock('../../hooks/useSkills', () => ({
  useInstallSkillFromMarketplace: () => ({
    isPending: false,
    variables: undefined,
    mutateAsync: vi.fn(),
  }),
  useSkillMarketplace: () => ({
    isLoading: false,
    isError: false,
    data: {
      available: true,
      sourceType: 'repository',
      sourceDirectory: 'https://github.com/alexk-dev/golemcore-skills',
      items: [],
    },
  }),
}));

vi.mock('../../hooks/useSettings', () => ({
  useRuntimeConfig: () => ({
    data: {
      skills: {
        enabled: true,
        progressiveLoading: true,
        marketplaceSourceType: 'repository',
        marketplaceRepositoryDirectory: null,
        marketplaceRepositoryUrl: 'https://github.com/alexk-dev/golemcore-skills',
        marketplaceBranch: 'main',
      },
    },
  }),
  useUpdateSkills: () => ({
    isPending: false,
    mutateAsync: vi.fn(),
  }),
}));

describe('SkillsMarketplacePanel', () => {
  it('renders marketplace source in a collapsed state by default', () => {
    const html = renderToStaticMarkup(<SkillsMarketplacePanel />);

    expect(html).toContain('Marketplace Source');
    expect(html).toContain('Edit source');
    expect(html).toContain('Current source');
    expect(html).not.toContain('Source type');
    expect(html).not.toContain('Save source');
  });
});
