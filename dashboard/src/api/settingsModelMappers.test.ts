import { describe, expect, it } from 'vitest';

import { toModelRouterConfig } from './settingsModelMappers';

describe('toModelRouterConfig', () => {
  it('normalizes mixed-case fallbackMode to lowercase enum', () => {
    const config = toModelRouterConfig({
      routing: { fallbackMode: 'Random' },
      tiers: { balanced: { fallbackMode: 'RANDOM' } },
    });

    expect(config.routing.fallbackMode).toBe('random');
    expect(config.tiers.balanced.fallbackMode).toBe('random');
  });

  it('defaults unknown fallbackMode to sequential', () => {
    const config = toModelRouterConfig({
      routing: { fallbackMode: 'parallel' },
    });

    expect(config.routing.fallbackMode).toBe('sequential');
  });

  it('accepts canonical lowercase sequential/random', () => {
    const config = toModelRouterConfig({
      routing: { fallbackMode: 'sequential' },
      tiers: { balanced: { fallbackMode: 'random' } },
    });

    expect(config.routing.fallbackMode).toBe('sequential');
    expect(config.tiers.balanced.fallbackMode).toBe('random');
  });
});
