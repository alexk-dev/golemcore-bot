import { describe, expect, it } from 'vitest';
import type { FileTreeNode } from '../../api/files';
import {
  filterFilesByQuery,
  findMentionTrigger,
  flattenFileTree,
  insertMentionPath,
  type MentionFileEntry,
} from './fileMentions';

describe('findMentionTrigger', () => {
  it('detects a mention token at the caret when the prefix starts with @ at word start', () => {
    const result = findMentionTrigger('hello @foo', 10);
    expect(result).toEqual({ query: 'foo', start: 6, end: 10 });
  });

  it('detects an empty query immediately after @', () => {
    const result = findMentionTrigger('hello @', 7);
    expect(result).toEqual({ query: '', start: 6, end: 7 });
  });

  it('returns null when there is no @ before the caret', () => {
    expect(findMentionTrigger('hello world', 11)).toBeNull();
  });

  it('returns null when the @ is followed by whitespace before the caret', () => {
    expect(findMentionTrigger('hello @ bar', 11)).toBeNull();
  });

  it('returns null when the @ is mid-word (e.g. email address)', () => {
    expect(findMentionTrigger('user@example', 12)).toBeNull();
  });

  it('detects a mention at the start of the input', () => {
    expect(findMentionTrigger('@src/', 5)).toEqual({ query: 'src/', start: 0, end: 5 });
  });
});

describe('filterFilesByQuery', () => {
  const files: MentionFileEntry[] = [
    { path: 'src/components/chat/ChatInput.tsx', name: 'ChatInput.tsx' },
    { path: 'src/components/chat/ChatWindow.tsx', name: 'ChatWindow.tsx' },
    { path: 'src/store/ideStore.ts', name: 'ideStore.ts' },
    { path: 'package.json', name: 'package.json' },
  ];

  it('returns all files for an empty query', () => {
    expect(filterFilesByQuery(files, '')).toHaveLength(4);
  });

  it('matches by substring in the path', () => {
    const result = filterFilesByQuery(files, 'Chat');
    expect(result).toHaveLength(2);
    expect(result[0]?.name).toBe('ChatInput.tsx');
  });

  it('is case-insensitive', () => {
    expect(filterFilesByQuery(files, 'idestore')).toHaveLength(1);
  });

  it('caps the result list at the supplied limit', () => {
    expect(filterFilesByQuery(files, '', 2)).toHaveLength(2);
  });
});

describe('flattenFileTree', () => {
  function makeNode(path: string, type: 'file' | 'directory', children: FileTreeNode[] = []): FileTreeNode {
    const name = path.split('/').pop() ?? path;
    return {
      path,
      name,
      type,
      binary: false,
      image: false,
      editable: true,
      hasChildren: children.length > 0,
      children,
    };
  }

  it('returns only file entries in depth-first order', () => {
    const tree: FileTreeNode[] = [
      makeNode('src', 'directory', [
        makeNode('src/a.ts', 'file'),
        makeNode('src/store', 'directory', [makeNode('src/store/x.ts', 'file')]),
      ]),
      makeNode('README.md', 'file'),
    ];
    const result = flattenFileTree(tree);
    expect(result.map((entry) => entry.path)).toEqual([
      'src/a.ts',
      'src/store/x.ts',
      'README.md',
    ]);
    expect(result[0]?.name).toBe('a.ts');
  });

  it('skips binary files', () => {
    const tree: FileTreeNode[] = [
      { ...makeNode('logo.png', 'file'), binary: true, image: true },
      makeNode('src/a.ts', 'file'),
    ];
    expect(flattenFileTree(tree).map((entry) => entry.path)).toEqual(['src/a.ts']);
  });
});

describe('insertMentionPath', () => {
  it('replaces the mention token with @<path> and trailing space', () => {
    const result = insertMentionPath('hello @foo', { start: 6, end: 10 }, 'src/foo.ts');
    expect(result.text).toBe('hello @src/foo.ts ');
    expect(result.caret).toBe('hello @src/foo.ts '.length);
  });

  it('preserves trailing text after the caret', () => {
    const result = insertMentionPath('hello @foo bar', { start: 6, end: 10 }, 'src/foo.ts');
    expect(result.text).toBe('hello @src/foo.ts  bar');
  });
});
