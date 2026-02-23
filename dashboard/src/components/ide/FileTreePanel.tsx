import type { ReactElement } from 'react';
import { Tree, type NodeApi, type NodeRendererProps } from 'react-arborist';
import { FiChevronDown, FiChevronRight, FiFile, FiFolder } from 'react-icons/fi';
import type { FileTreeNode } from '../../api/files';
import { useElementSize } from '../../hooks/useElementSize';

export interface FileTreePanelProps {
  nodes: FileTreeNode[];
  selectedPath: string | null;
  onOpenFile: (path: string) => void;
}

interface ArboristFileNode {
  id: string;
  name: string;
  kind: 'file' | 'directory';
  path: string;
  children?: ArboristFileNode[];
}

function mapNodes(inputNodes: FileTreeNode[]): ArboristFileNode[] {
  return inputNodes.map((node) => {
    const children = mapNodes(node.children);
    const mappedNode: ArboristFileNode = {
      id: node.path,
      name: node.name,
      kind: node.type,
      path: node.path,
    };
    if (children.length > 0) {
      mappedNode.children = children;
    }
    return mappedNode;
  });
}

function FileTreeNodeRenderer({ node, style, dragHandle }: NodeRendererProps<ArboristFileNode>): ReactElement {
  const icon = node.data.kind === 'directory' ? <FiFolder size={14} /> : <FiFile size={14} />;

  return (
    <div style={style} ref={dragHandle} className={`ide-tree-node ${node.isSelected ? 'selected' : ''}`}>
      <span className="ide-tree-chevron" aria-hidden>
        {node.data.kind === 'directory'
          ? (node.isOpen ? <FiChevronDown size={12} /> : <FiChevronRight size={12} />)
          : null}
      </span>
      <span className="ide-tree-icon" aria-hidden>{icon}</span>
      <span className="ide-tree-name text-truncate">{node.data.name}</span>
    </div>
  );
}

export function FileTreePanel({ nodes, selectedPath, onOpenFile }: FileTreePanelProps): ReactElement {
  const mappedNodes = mapNodes(nodes);
  const { ref, size } = useElementSize();

  const handleActivate = (node: NodeApi<ArboristFileNode>): void => {
    if (node.data.kind === 'file') {
      onOpenFile(node.data.path);
    }
  };

  return (
    <div className="ide-tree-panel h-100" ref={ref}>
      <Tree<ArboristFileNode>
        data={mappedNodes}
        width={size.width}
        height={Math.max(180, size.height)}
        rowHeight={30}
        indent={18}
        openByDefault={false}
        selection={selectedPath ?? undefined}
        onActivate={handleActivate}
        disableDrag
        disableEdit
      >
        {FileTreeNodeRenderer}
      </Tree>
    </div>
  );
}
