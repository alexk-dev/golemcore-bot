import { describe, expect, it } from 'vitest';
import {
  formatUpdateTimestamp,
  getSidebarUpdateBadge,
  getUpdateStateDescription,
  getUpdateStateLabel,
  getUpdateStateVariant,
} from './systemUpdateUi';

describe('systemUpdateUi', () => {
  it('shouldFormatUpdateTimestampWithFallbacks', () => {
    expect(formatUpdateTimestamp(null)).toBe('N/A');
    expect(formatUpdateTimestamp('  ')).toBe('N/A');
    expect(formatUpdateTimestamp('not-a-date')).toBe('not-a-date');

    const formatted = formatUpdateTimestamp('2026-02-20T12:00:00Z');
    expect(formatted).not.toBe('N/A');
  });

  it('shouldResolveUpdateStateVariantByState', () => {
    expect(getUpdateStateVariant('FAILED')).toBe('danger');
    expect(getUpdateStateVariant('AVAILABLE')).toBe('warning');
    expect(getUpdateStateVariant('STAGED')).toBe('warning');
    expect(getUpdateStateVariant('CHECKING')).toBe('info');
    expect(getUpdateStateVariant('DISABLED')).toBe('secondary');
    expect(getUpdateStateVariant('IDLE')).toBe('success');
  });

  it('shouldResolveUpdateLabelsAndDescriptionsWithFallback', () => {
    expect(getUpdateStateLabel('FAILED')).toBe('Failed');
    expect(getUpdateStateLabel('CUSTOM')).toBe('CUSTOM');
    expect(getUpdateStateDescription('FAILED')).toContain('failed');
    expect(getUpdateStateDescription('CUSTOM')).toBe('Unknown state');
  });

  it('shouldBuildSidebarBadgesForImportantStates', () => {
    expect(getSidebarUpdateBadge('AVAILABLE')).toEqual({
      label: 'NEW',
      variant: 'warning',
      text: 'dark',
      title: 'New update is available',
    });
    expect(getSidebarUpdateBadge('STAGED')).toEqual({
      label: 'STAGED',
      variant: 'info',
      text: 'white',
      title: 'Update is staged and ready to apply',
    });
    expect(getSidebarUpdateBadge('FAILED')).toEqual({
      label: 'ERR',
      variant: 'danger',
      text: 'white',
      title: 'Last update operation failed',
    });
    expect(getSidebarUpdateBadge('IDLE')).toBeNull();
  });
});
