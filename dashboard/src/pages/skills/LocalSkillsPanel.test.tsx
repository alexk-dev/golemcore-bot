import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { LocalSkillsPanel } from './LocalSkillsPanel';

describe('LocalSkillsPanel', () => {
  it('renders empty-state guidance when there are no local skills', () => {
    const html = renderToStaticMarkup(
      <LocalSkillsPanel
        detail={undefined}
        detailError={false}
        detailLoading={false}
        filteredSkills={[]}
        onDelete={vi.fn()}
        onOpenMarketplace={vi.fn()}
        onRefetchDetail={vi.fn()}
        onSave={vi.fn(async () => ({
          name: 'test-skill',
          description: '',
          available: true,
          hasMcp: false,
        }))}
        onSearchChange={vi.fn()}
        onSelectSkill={vi.fn()}
        searchQuery=""
        selectedSkillName={null}
        updatePending={false}
        deletePending={false}
      />,
    );

    expect(html).toContain('No skills match this filter.');
    expect(html).toContain('Open marketplace');
    expect(html).toContain('Select a skill to edit');
  });

  it('renders loading state while a selected skill is being fetched', () => {
    const html = renderToStaticMarkup(
      <LocalSkillsPanel
        detail={undefined}
        detailError={false}
        detailLoading
        filteredSkills={[
          {
            name: 'golemcore/code-reviewer',
            description: 'Review changes',
            available: true,
            hasMcp: true,
            modelTier: 'coding',
          },
        ]}
        onDelete={vi.fn()}
        onOpenMarketplace={vi.fn()}
        onRefetchDetail={vi.fn()}
        onSave={vi.fn(async () => ({
          name: 'golemcore/code-reviewer',
          description: 'Review changes',
          available: true,
          hasMcp: true,
        }))}
        onSearchChange={vi.fn()}
        onSelectSkill={vi.fn()}
        searchQuery=""
        selectedSkillName="golemcore/code-reviewer"
        updatePending={false}
        deletePending={false}
      />,
    );

    expect(html).toContain('Loading skill...');
  });
});
