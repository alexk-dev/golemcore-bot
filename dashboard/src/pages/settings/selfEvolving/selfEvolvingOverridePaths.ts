export function hasOverriddenPath(overriddenPaths: string[] | undefined, path: string): boolean {
  return (overriddenPaths ?? []).includes(path);
}

export function hasOverriddenPathPrefix(overriddenPaths: string[] | undefined, prefix: string): boolean {
  return (overriddenPaths ?? []).some((path) => path === prefix || path.startsWith(`${prefix}.`));
}

export function formatOverriddenPathList(overriddenPaths: string[] | undefined): string {
  const paths = overriddenPaths ?? [];
  return paths.join(', ');
}
