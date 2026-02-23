const STRICT_PATTERN = /^[a-zA-Z0-9_-]{8,64}$/;
const LEGACY_PATTERN = /^[a-zA-Z0-9_-]{1,64}$/;

export function normalizeConversationKey(value: string | null | undefined): string | null {
  if (value == null) {
    return null;
  }
  const normalized = value.trim();
  if (normalized.length === 0) {
    return null;
  }
  return normalized;
}

export function isStrictConversationKey(value: string | null | undefined): boolean {
  const normalized = normalizeConversationKey(value);
  return normalized != null && STRICT_PATTERN.test(normalized);
}

export function isLegacyCompatibleConversationKey(value: string | null | undefined): boolean {
  const normalized = normalizeConversationKey(value);
  return normalized != null && LEGACY_PATTERN.test(normalized);
}
