import { describe, expect, it } from 'vitest';
import { describeReferences, filterMemoryItems, formatRelativeTime, getTypeLabel, getTypeTone } from './memoryFormat';
import type { RelevantMemoryItem } from '../../../../api/memory';

function memory(overrides: Partial<RelevantMemoryItem> & Pick<RelevantMemoryItem, 'id'>): RelevantMemoryItem {
  return {
    layer: null,
    type: null,
    title: null,
    content: null,
    scope: null,
    tags: [],
    source: null,
    confidence: null,
    salience: null,
    ttlDays: null,
    createdAt: null,
    updatedAt: null,
    lastAccessedAt: null,
    references: [],
    referenceCount: 0,
    ...overrides,
  };
}

describe('getTypeLabel / getTypeTone', () => {
  it('returns the human label for a known type', () => {
    expect(getTypeLabel('PROJECT_FACT')).toBe('Fact');
    expect(getTypeLabel('PREFERENCE')).toBe('Preference');
  });

  it('falls back to "Memory" for null', () => {
    expect(getTypeLabel(null)).toBe('Memory');
  });

  it('maps types to tones', () => {
    expect(getTypeTone('PROJECT_FACT')).toBe('fact');
    expect(getTypeTone('PREFERENCE')).toBe('preference');
    expect(getTypeTone('CONSTRAINT')).toBe('constraint');
    expect(getTypeTone('FAILURE')).toBe('failure');
    expect(getTypeTone(null)).toBe('neutral');
  });
});

describe('formatRelativeTime', () => {
  const now = Date.parse('2026-04-30T10:00:00Z');

  it('returns "Just now" for the past 45 seconds', () => {
    expect(formatRelativeTime('2026-04-30T09:59:30Z', now)).toBe('Just now');
  });

  it('renders minutes', () => {
    expect(formatRelativeTime('2026-04-30T09:55:00Z', now)).toBe('5 mins ago');
  });

  it('renders hours', () => {
    expect(formatRelativeTime('2026-04-30T07:00:00Z', now)).toBe('3 hours ago');
  });

  it('renders days', () => {
    expect(formatRelativeTime('2026-04-28T10:00:00Z', now)).toBe('2 days ago');
  });

  it('handles null timestamps', () => {
    expect(formatRelativeTime(null, now)).toBe('—');
  });
});

describe('describeReferences', () => {
  it('says "In session" for zero references', () => {
    expect(describeReferences(memory({ id: 'a', referenceCount: 0 }))).toBe('In session');
  });

  it('pluralises sessions', () => {
    expect(describeReferences(memory({ id: 'b', referenceCount: 1 }))).toBe('In 1 session');
    expect(describeReferences(memory({ id: 'c', referenceCount: 4 }))).toBe('In 4 sessions');
  });
});

describe('filterMemoryItems', () => {
  const items = [
    memory({ id: 'a', type: 'PROJECT_FACT', title: 'Optimizer config path', content: 'Path is /opt/foo' }),
    memory({ id: 'b', type: 'PREFERENCE', title: 'Target metrics', content: 'Net uplift first', tags: ['target'] }),
    memory({ id: 'c', type: 'CONSTRAINT', title: 'Risk limits', content: 'Max drawdown 15%' }),
  ];

  it('filters by type', () => {
    expect(filterMemoryItems(items, 'PROJECT_FACT', '').map((item) => item.id)).toEqual(['a']);
  });

  it('filters by search across title, content and tags', () => {
    expect(filterMemoryItems(items, 'all', 'metrics').map((item) => item.id)).toEqual(['b']);
    expect(filterMemoryItems(items, 'all', 'target').map((item) => item.id)).toEqual(['b']);
  });

  it('returns all when neither filter is set', () => {
    expect(filterMemoryItems(items, 'all', '')).toHaveLength(3);
  });
});
