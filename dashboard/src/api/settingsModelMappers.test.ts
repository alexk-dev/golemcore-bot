import { describe, expect, it } from 'vitest';

import { toModelRouterConfig } from './settingsModelMappers';

describe('toModelRouterConfig', () => {
  it('normalizes mixed-case fallbackMode to canonical enum values', () => {
    const config = toModelRouterConfig({
      routing: { fallbackMode: 'Round_Robin' },
      tiers: { balanced: { fallbackMode: 'WEIGHTED' } },
    });

    expect(config.routing.fallbackMode).toBe('round_robin');
    expect(config.tiers.balanced.fallbackMode).toBe('weighted');
  });

  it('defaults unknown fallbackMode to sequential', () => {
    const config = toModelRouterConfig({
      routing: { fallbackMode: 'parallel' },
    });

    expect(config.routing.fallbackMode).toBe('sequential');
  });

  it('accepts canonical lowercase fallback modes', () => {
    const config = toModelRouterConfig({
      routing: { fallbackMode: 'sequential' },
      tiers: {
        balanced: { fallbackMode: 'round_robin' },
        smart: { fallbackMode: 'weighted' },
      },
    });

    expect(config.routing.fallbackMode).toBe('sequential');
    expect(config.tiers.balanced.fallbackMode).toBe('round_robin');
    expect(config.tiers.smart.fallbackMode).toBe('weighted');
  });

  it('preserves non-negative fallback weights', () => {
    const config = toModelRouterConfig({
      tiers: {
        balanced: {
          fallbacks: [
            { model: { provider: 'openai', id: 'gpt-5.1' }, weight: 2.5 },
            { model: { provider: 'openai', id: 'gpt-5-mini' }, weight: -1 },
          ],
        },
      },
    });

    expect(config.tiers.balanced.fallbacks[0].weight).toBe(2.5);
    expect(config.tiers.balanced.fallbacks[1].weight).toBeNull();
  });
});
