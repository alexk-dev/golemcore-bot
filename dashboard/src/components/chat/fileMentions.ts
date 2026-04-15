import type { FileTreeNode } from '../../api/files';

export interface MentionFileEntry {
  path: string;
  name: string;
}

export function flattenFileTree(tree: FileTreeNode[]): MentionFileEntry[] {
  const result: MentionFileEntry[] = [];
  const stack: FileTreeNode[] = [];
  for (let index = tree.length - 1; index >= 0; index -= 1) {
    stack.push(tree[index]);
  }
  while (stack.length > 0) {
    const node = stack.pop();
    if (!node) {
      continue;
    }
    if (node.type === 'file') {
      if (!node.binary) {
        result.push({ path: node.path, name: node.name });
      }
      continue;
    }
    for (let index = node.children.length - 1; index >= 0; index -= 1) {
      stack.push(node.children[index]);
    }
  }
  return result;
}

export interface MentionTrigger {
  query: string;
  start: number;
  end: number;
}

export interface MentionInsertResult {
  text: string;
  caret: number;
}

export function findMentionTrigger(text: string, caret: number): MentionTrigger | null {
  if (caret < 1) {
    return null;
  }
  const prefix = text.slice(0, caret);
  const atIndex = prefix.lastIndexOf('@');
  if (atIndex < 0) {
    return null;
  }
  const precedingChar = atIndex === 0 ? '' : prefix.charAt(atIndex - 1);
  if (precedingChar !== '' && !isMentionBoundary(precedingChar)) {
    return null;
  }
  const query = prefix.slice(atIndex + 1);
  if (/\s/.test(query)) {
    return null;
  }
  return { query, start: atIndex, end: caret };
}

export function filterFilesByQuery(
  files: MentionFileEntry[],
  query: string,
  limit = 20,
): MentionFileEntry[] {
  const normalized = query.toLowerCase();
  const matched = normalized.length === 0
    ? files
    : files.filter((file) => file.path.toLowerCase().includes(normalized));
  return matched.slice(0, limit);
}

export function insertMentionPath(
  text: string,
  trigger: { start: number; end: number },
  path: string,
): MentionInsertResult {
  const before = text.slice(0, trigger.start);
  const after = text.slice(trigger.end);
  const insertion = `@${path} `;
  return {
    text: `${before}${insertion}${after}`,
    caret: before.length + insertion.length,
  };
}

function isMentionBoundary(char: string): boolean {
  return /\s/.test(char);
}
