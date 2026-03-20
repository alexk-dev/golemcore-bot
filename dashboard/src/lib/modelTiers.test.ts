import { describe, expect, it } from 'vitest';
import { allowsEmptyModelSelection } from './modelTiers';

describe('modelTiers', () => {
  it('marks special tiers as optional model slots', () => {
    expect(allowsEmptyModelSelection('special1')).toBe(true);
    expect(allowsEmptyModelSelection('special5')).toBe(true);
  });

  it('keeps public routing tiers non-optional', () => {
    expect(allowsEmptyModelSelection('balanced')).toBe(false);
    expect(allowsEmptyModelSelection('smart')).toBe(false);
    expect(allowsEmptyModelSelection('deep')).toBe(false);
    expect(allowsEmptyModelSelection('coding')).toBe(false);
    expect(allowsEmptyModelSelection('routing')).toBe(false);
  });
});
