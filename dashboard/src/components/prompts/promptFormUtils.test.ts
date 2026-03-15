import { describe, expect, it } from 'vitest';
import type { PromptSection } from '../../api/prompts';
import {
  arePromptDraftsEqual,
  buildPromptReorderRequests,
  buildPromptMetrics,
  DEFAULT_PROMPT_ORDER,
  findPriorityConflictName,
  getNextPromptOrder,
  isPromptNameValid,
  normalizePromptName,
  parsePromptOrder,
  reorderPromptSections,
  toPromptDraft,
  toPromptPayload,
} from './promptFormUtils';

function createSection(overrides: Partial<PromptSection> = {}): PromptSection {
  return {
    name: 'custom',
    description: 'Custom section',
    order: 100,
    enabled: true,
    deletable: true,
    content: 'Prompt body',
    ...overrides,
  };
}

describe('promptFormUtils', () => {
  it('normalizes prompt names to lowercase slugs', () => {
    expect(normalizePromptName('  Rules-Extra  ')).toBe('rules-extra');
  });

  it('validates prompt names against lowercase slug pattern', () => {
    expect(isPromptNameValid('custom-rule')).toBe(true);
    expect(isPromptNameValid('Custom Rule')).toBe(false);
    expect(isPromptNameValid('custom_rule')).toBe(false);
  });

  it('calculates the next prompt order from the current maximum', () => {
    const sections = [
      createSection({ name: 'identity', order: 10 }),
      createSection({ name: 'rules', order: 20 }),
      createSection({ name: 'custom', order: 55 }),
    ];

    expect(getNextPromptOrder(sections)).toBe(65);
    expect(getNextPromptOrder([])).toBe(DEFAULT_PROMPT_ORDER);
  });

  it('parses numeric order input and falls back on invalid values', () => {
    expect(parsePromptOrder('42', 100)).toBe(42);
    expect(parsePromptOrder('not-a-number', 100)).toBe(100);
  });

  it('maps prompt sections to editable drafts and back to payloads', () => {
    const section = createSection({
      name: 'identity',
      description: 'Identity',
      order: 10,
      enabled: false,
      deletable: false,
      content: 'You are a bot',
    });

    const draft = toPromptDraft(section);

    expect(draft).toEqual({
      name: 'identity',
      description: 'Identity',
      order: 10,
      enabled: false,
      deletable: false,
      content: 'You are a bot',
    });
    expect(toPromptPayload(draft)).toEqual({
      description: 'Identity',
      order: 10,
      enabled: false,
      content: 'You are a bot',
    });
  });

  it('detects whether a draft has unsaved changes', () => {
    const section = createSection();
    const draft = toPromptDraft(section);

    expect(arePromptDraftsEqual(draft, section)).toBe(true);
    expect(arePromptDraftsEqual({ ...draft, content: 'Updated body' }, section)).toBe(false);
  });

  it('finds prompt sections with conflicting priority values', () => {
    const sections = [
      createSection({ name: 'identity', order: 10, deletable: false }),
      createSection({ name: 'rules', order: 20, deletable: false }),
      createSection({ name: 'custom', order: 30 }),
    ];

    expect(findPriorityConflictName(toPromptDraft(sections[0]), sections)).toBeNull();
    expect(findPriorityConflictName({ ...toPromptDraft(sections[2]), order: 20 }, sections)).toBe('rules');
  });

  it('builds prompt metrics for sidebar summaries', () => {
    const sections = [
      createSection({ name: 'identity', enabled: true, deletable: false }),
      createSection({ name: 'rules', enabled: true, deletable: false }),
      createSection({ name: 'custom', enabled: false, deletable: true }),
    ];

    expect(buildPromptMetrics(sections)).toEqual({
      total: 3,
      enabled: 2,
      protected: 2,
    });
  });

  it('reorders prompt sections and normalizes priority spacing', () => {
    const sections = [
      createSection({ name: 'identity', order: 10 }),
      createSection({ name: 'rules', order: 20 }),
      createSection({ name: 'custom', order: 30 }),
    ];

    expect(reorderPromptSections(sections, 'custom', 'identity')).toEqual([
      createSection({ name: 'custom', order: 10 }),
      createSection({ name: 'identity', order: 20 }),
      createSection({ name: 'rules', order: 30 }),
    ]);
  });

  it('creates update requests only for prompts whose order changed during reorder', () => {
    const previousSections = [
      createSection({ name: 'identity', order: 10 }),
      createSection({ name: 'rules', order: 20 }),
      createSection({ name: 'custom', order: 30 }),
    ];
    const nextSections = reorderPromptSections(previousSections, 'custom', 'identity');

    expect(buildPromptReorderRequests(previousSections, nextSections)).toEqual([
      {
        name: 'custom',
        section: {
          description: 'Custom section',
          order: 10,
          enabled: true,
          content: 'Prompt body',
        },
      },
      {
        name: 'identity',
        section: {
          description: 'Custom section',
          order: 20,
          enabled: true,
          content: 'Prompt body',
        },
      },
      {
        name: 'rules',
        section: {
          description: 'Custom section',
          order: 30,
          enabled: true,
          content: 'Prompt body',
        },
      },
    ]);
  });
});
