function normalize(value: string | null | undefined): string {
  return value?.trim() ?? '';
}

export function usesImplicitProviderPrefix(provider: string | null | undefined): boolean {
  return normalize(provider).toLowerCase() === 'openrouter';
}

export function toProviderScopedModelId(id: string | null | undefined, provider: string | null | undefined): string {
  const normalizedId = normalize(id);
  const normalizedProvider = normalize(provider);
  if (
    normalizedId.length === 0
    || normalizedProvider.length === 0
    || normalizedId.startsWith(`${normalizedProvider}/`)
  ) {
    return normalizedId;
  }
  return `${normalizedProvider}/${normalizedId}`;
}

export function toPersistedModelIdForProvider(id: string | null | undefined, provider: string | null | undefined): string {
  const normalizedId = normalize(id);
  if (normalizedId.length === 0) {
    return '';
  }
  return usesImplicitProviderPrefix(provider)
    ? toProviderScopedModelId(normalizedId, provider)
    : normalizedId;
}

export function toEditorModelIdForProvider(id: string | null | undefined, provider: string | null | undefined): string {
  const normalizedId = normalize(id);
  const normalizedProvider = normalize(provider);
  if (
    normalizedId.length === 0
    || normalizedProvider.length === 0
    || !usesImplicitProviderPrefix(normalizedProvider)
    || !normalizedId.startsWith(`${normalizedProvider}/`)
  ) {
    return normalizedId;
  }
  return normalizedId.slice(normalizedProvider.length + 1);
}
