import type { FileTreeNode } from '../api/files';

function replaceDirectoryChildren(
  nodes: FileTreeNode[],
  directoryPath: string,
  children: FileTreeNode[],
): { nodes: FileTreeNode[]; changed: boolean } {
  let changed = false;
  const nextNodes = nodes.map((node) => {
    if (node.path === directoryPath && node.type === 'directory') {
      changed = true;
      return {
        ...node,
        hasChildren: children.length > 0 || node.hasChildren,
        children,
      };
    }

    if (node.children.length === 0) {
      return node;
    }

    const nextChildren = replaceDirectoryChildren(node.children, directoryPath, children);
    if (!nextChildren.changed) {
      return node;
    }

    changed = true;
    return {
      ...node,
      children: nextChildren.nodes,
    };
  });

  return { nodes: changed ? nextNodes : nodes, changed };
}

export function mergeDirectoryChildren(
  nodes: FileTreeNode[],
  directoryPath: string,
  children: FileTreeNode[],
): FileTreeNode[] {
  if (directoryPath.length === 0) {
    return children;
  }

  return replaceDirectoryChildren(nodes, directoryPath, children).nodes;
}
