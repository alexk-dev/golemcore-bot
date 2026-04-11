export type UnknownRecord = Record<string, unknown>;

export function hasSecretValue(secret: unknown): boolean {
  if (secret == null || typeof secret !== 'object') {
    return false;
  }
  const record = secret as UnknownRecord;
  const value = record.value;
  const present = record.present;
  return Boolean(present) || (typeof value === 'string' && value.length > 0);
}

export function toNullableString(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

export function toStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((entry): entry is string => typeof entry === 'string')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
}
