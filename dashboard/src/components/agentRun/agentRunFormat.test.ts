import { describe, expect, it } from 'vitest';
import { formatCountdown, formatDuration, formatTimeOfDay } from './agentRunFormat';

describe('formatDuration', () => {
  it('renders milliseconds for sub-second values', () => {
    expect(formatDuration(180)).toBe('180ms');
  });

  it('renders seconds with one decimal under a minute', () => {
    expect(formatDuration(12_400)).toBe('12.4s');
  });

  it('rounds whole seconds without trailing .0', () => {
    expect(formatDuration(7_000)).toBe('7s');
  });

  it('renders minutes and seconds for longer durations', () => {
    expect(formatDuration(125_000)).toBe('2m 05s');
  });

  it('treats negative durations as zero', () => {
    expect(formatDuration(-1)).toBe('0s');
  });
});

describe('formatCountdown', () => {
  it('zero pads minutes and seconds', () => {
    expect(formatCountdown(75)).toBe('01:15');
  });

  it('clamps negative values', () => {
    expect(formatCountdown(-5)).toBe('00:00');
  });
});

describe('formatTimeOfDay', () => {
  it('returns empty string when iso is null', () => {
    expect(formatTimeOfDay(null)).toBe('');
  });

  it('returns the original string when invalid', () => {
    expect(formatTimeOfDay('not a date')).toBe('not a date');
  });
});
