import { describe, expect, it } from 'vitest';

import { filterCatalogBlocks, matchesSettingsTitle } from './settingsCatalogSearch';

describe('matchesSettingsTitle', () => {
  it('matches direct title fragments', () => {
    expect(matchesSettingsTitle('Voice Routing', 'voice')).toBe(true);
  });

  it('matches common typos through Levenshtein distance', () => {
    expect(matchesSettingsTitle('Voice Routing', 'voise routng')).toBe(true);
    expect(matchesSettingsTitle('Model Catalog', 'modle catlog')).toBe(true);
  });

  it('rejects unrelated queries', () => {
    expect(matchesSettingsTitle('Voice Routing', 'banana')).toBe(false);
  });
});

describe('filterCatalogBlocks', () => {
  it('keeps only matching items and removes empty blocks', () => {
    const blocks = [
      {
        key: 'core',
        title: 'Core',
        items: [
          { key: 'models', title: 'Model Router' },
          { key: 'general', title: 'General' },
        ],
      },
      {
        key: 'tools',
        title: 'Tools',
        items: [
          { key: 'voice', title: 'Voice Routing' },
        ],
      },
    ];

    expect(filterCatalogBlocks(blocks, 'voise')).toEqual([
      {
        key: 'tools',
        title: 'Tools',
        items: [
          { key: 'voice', title: 'Voice Routing' },
        ],
      },
    ]);
  });

  it('can find the tracing section with a trace query', () => {
    const blocks = [
      {
        key: 'runtime',
        title: 'Runtime',
        items: [
          { key: 'tracing', title: 'Tracing' },
          { key: 'auto', title: 'Auto Mode' },
        ],
      },
    ];

    expect(filterCatalogBlocks(blocks, 'trace')).toEqual([
      {
        key: 'runtime',
        title: 'Runtime',
        items: [
          { key: 'tracing', title: 'Tracing' },
        ],
      },
    ]);
  });
});
