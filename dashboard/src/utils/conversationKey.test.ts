import { describe, expect, it } from 'vitest';
import {
  isLegacyCompatibleConversationKey,
  isStrictConversationKey,
  normalizeConversationKey,
} from './conversationKey';

describe('conversationKey', () => {
  it('normalizes by trimming surrounding whitespace', () => {
    expect(normalizeConversationKey('  conv_1234  ')).toBe('conv_1234');
    expect(normalizeConversationKey('   ')).toBeNull();
  });

  it('validates strict contract for new keys', () => {
    expect(isStrictConversationKey('conv_1234')).toBe(true);
    expect(isStrictConversationKey('legacy7')).toBe(false);
    expect(isStrictConversationKey('a'.repeat(65))).toBe(false);
  });

  it('validates legacy-compatible keys for existing sessions', () => {
    expect(isLegacyCompatibleConversationKey('legacy7')).toBe(true);
    expect(isLegacyCompatibleConversationKey('conv_1234')).toBe(true);
    expect(isLegacyCompatibleConversationKey('bad:key')).toBe(false);
    expect(isLegacyCompatibleConversationKey('bad key')).toBe(false);
  });
});
