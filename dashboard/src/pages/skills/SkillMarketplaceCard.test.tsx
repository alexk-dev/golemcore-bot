import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { SkillMarketplaceItem } from '../../api/skills';
import { SkillMarketplaceCard } from './SkillMarketplaceCard';

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
    modelTier: 'smart',
    sourcePath: 'registry/golemcore/devops-pack/artifact.yaml',
    skillRefs: [
      'golemcore/devops-pack/deploy-review',
      'golemcore/devops-pack/incident-triage',
      'golemcore/devops-pack/release-check',
      'golemcore/devops-pack/rollback-plan',
      'golemcore/devops-pack/postmortem',
    ],
    skillCount: 5,
    installed: false,
    updateAvailable: false,
    ...overrides,
  };
}

describe('SkillMarketplaceCard', () => {
  it('renders installed pack state with overflowed runtime refs', () => {
    const html = renderToStaticMarkup(
      <SkillMarketplaceCard
        item={createItem({ installed: true })}
        isPending={false}
        pendingSkillId={null}
        onInstall={() => undefined}
      />,
    );

    expect(html).toContain('Installed');
    expect(html).toContain('Pack');
    expect(html).toContain('+1 more');
    expect(html).toContain('disabled');
  });

  it('renders update action when artifact has a newer version available', () => {
    const html = renderToStaticMarkup(
      <SkillMarketplaceCard
        item={createItem({ installed: true, updateAvailable: true })}
        isPending={false}
        pendingSkillId={null}
        onInstall={() => undefined}
      />,
    );

    expect(html).toContain('Update');
    expect(html).not.toContain('disabled=""');
  });
});
