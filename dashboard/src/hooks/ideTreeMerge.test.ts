import { describe, expect, it } from 'vitest';
import type { FileTreeNode } from '../api/files';
import { mergeDirectoryChildren } from './ideTreeMerge';

function directoryNode(path: string, children: FileTreeNode[] = []): FileTreeNode {
  const parts = path.split('/');
  return {
    path,
    name: parts[parts.length - 1] ?? path,
    type: 'directory',
    binary: false,
    image: false,
    editable: false,
    hasChildren: true,
    children,
  };
}

function fileNode(path: string): FileTreeNode {
  const parts = path.split('/');
  return {
    path,
    name: parts[parts.length - 1] ?? path,
    type: 'file',
    binary: false,
    image: false,
    editable: true,
    hasChildren: false,
    children: [],
  };
}

describe('mergeDirectoryChildren', () => {
  it('replaces root nodes when the root directory is loaded', () => {
    const nextChildren = [directoryNode('src')];

    const merged = mergeDirectoryChildren([fileNode('README.md')], '', nextChildren);

    expect(merged).toEqual(nextChildren);
  });

  it('merges loaded children into the matching nested directory', () => {
    const tree = [directoryNode('src', [directoryNode('src/main')])];
    const loadedChildren = [fileNode('src/main/App.java')];

    const merged = mergeDirectoryChildren(tree, 'src/main', loadedChildren);

    expect(merged[0]?.children[0]?.children).toEqual(loadedChildren);
    expect(merged[0]?.children[0]?.hasChildren).toBe(true);
  });

  it('preserves untouched branches by reference', () => {
    const untouched = directoryNode('docs', [fileNode('docs/README.md')]);
    const tree = [directoryNode('src'), untouched];

    const merged = mergeDirectoryChildren(tree, 'src', [fileNode('src/App.tsx')]);

    expect(merged[1]).toBe(untouched);
  });
});
