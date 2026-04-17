import type { FileTreeNode } from '../../api/files';

/**
 * Flat file suggestion entry shown by the chat file-mention UI.
 */
export interface MentionFileEntry {
  path: string;
  name: string;
}

/**
 * Flattens a hierarchical file tree into mentionable non-binary file entries.
 */
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

/**
 * Active mention token boundaries inside the composer text.
 */
export interface MentionTrigger {
  query: string;
  start: number;
  end: number;
}

/**
 * Result of inserting a selected mention into the composer text.
 */
export interface MentionInsertResult {
  text: string;
  caret: number;
}

/**
 * Finds the currently active `@path` mention token at the caret position.
 */
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

/**
 * Filters flattened file entries against the active mention query.
 */
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

/**
 * Inserts a selected mention path and returns the updated text with caret position.
 */
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
