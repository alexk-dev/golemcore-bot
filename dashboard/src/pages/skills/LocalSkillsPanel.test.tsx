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
        editorContent=""
        filteredSkills={[]}
        isSkillDirty={false}
        onDelete={vi.fn()}
        onEditorChange={vi.fn()}
        onOpenMarketplace={vi.fn()}
        onRefetchDetail={vi.fn()}
        onSave={vi.fn()}
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
});
