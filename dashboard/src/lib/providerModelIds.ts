function normalize(value: string | null | undefined): string {
  return value?.trim() ?? '';
}

/**
 * Builds the canonical persisted model ID: always {@code provider/rawId}.
 * The provider prefix prevents collisions when different providers expose
 * models with the same raw ID (e.g. "gpt-5.4" from both openai and a
 * custom proxy).
 */
export function toPersistedModelIdForProvider(id: string | null | undefined, provider: string | null | undefined): string {
  const normalizedId = normalize(id);
  const normalizedProvider = normalize(provider);
  if (normalizedId.length === 0 || normalizedProvider.length === 0) {
    return normalizedId;
  }
  if (normalizedId.startsWith(`${normalizedProvider}/`)) {
    return normalizedId;
  }
  return `${normalizedProvider}/${normalizedId}`;
}

/**
 * Strips the provider prefix for display in the editor.
 * The user always sees the raw model ID without provider scope.
 */
export function toEditorModelIdForProvider(id: string | null | undefined, provider: string | null | undefined): string {
  const normalizedId = normalize(id);
  const normalizedProvider = normalize(provider);
  if (
    normalizedId.length === 0
    || normalizedProvider.length === 0
    || !normalizedId.startsWith(`${normalizedProvider}/`)
  ) {
    return normalizedId;
  }
  return normalizedId.slice(normalizedProvider.length + 1);
}

/**
 * @deprecated Legacy compat — all providers now use implicit prefix.
 * Kept so callers that branch on this can be migrated incrementally.
 */
export function usesImplicitProviderPrefix(_provider: string | null | undefined): boolean {
  return true;
}

/** @deprecated Use {@link toPersistedModelIdForProvider} directly. */
export function toProviderScopedModelId(id: string | null | undefined, provider: string | null | undefined): string {
  return toPersistedModelIdForProvider(id, provider);
}
