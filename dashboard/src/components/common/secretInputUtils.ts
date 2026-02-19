export function getSecretPlaceholder(hasStoredSecret: boolean, emptyPlaceholder: string): string {
  return hasStoredSecret ? 'Secret is configured (hidden)' : emptyPlaceholder;
}

export function getSecretInputType(showSecret: boolean): 'text' | 'password' {
  return showSecret ? 'text' : 'password';
}

export function getSecretToggleLabel(showSecret: boolean): string {
  return showSecret ? 'Hide' : 'Show';
}
