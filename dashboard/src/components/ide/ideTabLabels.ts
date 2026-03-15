export interface IdeTabLabel {
  path: string;
  title: string;
  context: string | null;
  fullTitle: string;
}

function splitPath(path: string): string[] {
  return path.split('/').filter((segment) => segment.length > 0);
}

export function getFilename(path: string): string {
  const segments = splitPath(path);
  return segments[segments.length - 1] ?? path;
}

function getParentSegments(path: string): string[] {
  const segments = splitPath(path);
  return segments.slice(0, -1);
}

function buildContext(path: string, depth: number): string {
  const parents = getParentSegments(path);
  if (parents.length === 0) {
    return 'root';
  }

  const suffixDepth = Math.max(1, depth);
  return parents.slice(-suffixDepth).join('/');
}

function buildFullTitle(title: string, context: string | null): string {
  return context == null ? title : `${title} · ${context}`;
}

function resolveContext(path: string, groupPaths: string[]): string {
  const maxDepth = Math.max(1, getParentSegments(path).length);

  for (let depth = 1; depth <= maxDepth; depth += 1) {
    const candidate = buildContext(path, depth);
    const isUnique = groupPaths.every((otherPath) => {
      if (otherPath === path) {
        return true;
      }

      return buildContext(otherPath, depth) !== candidate;
    });

    if (isUnique) {
      return candidate;
    }
  }

  return buildContext(path, maxDepth);
}

export function buildIdeTabLabels(paths: string[]): Map<string, IdeTabLabel> {
  const uniquePaths = Array.from(new Set(paths));
  const groups = new Map<string, string[]>();

  uniquePaths.forEach((path) => {
    const title = getFilename(path);
    const group = groups.get(title);
    if (group == null) {
      groups.set(title, [path]);
      return;
    }
    group.push(path);
  });

  const labels = new Map<string, IdeTabLabel>();

  groups.forEach((groupPaths, title) => {
    if (groupPaths.length === 1) {
      const path = groupPaths[0];
      if (path == null) {
        return;
      }
      labels.set(path, {
        path,
        title,
        context: null,
        fullTitle: title,
      });
      return;
    }

    groupPaths.forEach((path) => {
      const context = resolveContext(path, groupPaths);
      labels.set(path, {
        path,
        title,
        context,
        fullTitle: buildFullTitle(title, context),
      });
    });
  });

  return labels;
}
