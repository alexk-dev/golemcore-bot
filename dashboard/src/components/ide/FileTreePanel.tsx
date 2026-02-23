import { useEffect, useMemo, useState, type MouseEvent, type ReactElement } from 'react';
import { Tree, type NodeApi, type NodeRendererProps } from 'react-arborist';
import { FiChevronDown, FiChevronRight, FiFile, FiFolder } from 'react-icons/fi';
import type { FileTreeNode } from '../../api/files';
import { useElementSize } from '../../hooks/useElementSize';

export interface FileTreePanelProps {
  nodes: FileTreeNode[];
  selectedPath: string | null;
  dirtyPaths: Set<string>;
  searchQuery: string;
  onOpenFile: (path: string) => void;
  onRequestCreate: (targetPath: string) => void;
  onRequestRename: (targetPath: string) => void;
  onRequestDelete: (targetPath: string) => void;
}

interface ArboristFileNode {
  id: string;
  name: string;
  kind: 'file' | 'directory';
  path: string;
  isDirty: boolean;
  children?: ArboristFileNode[];
}

interface ContextMenuState {
  x: number;
  y: number;
  path: string;
  kind: 'file' | 'directory';
}

function mapNodes(inputNodes: FileTreeNode[], dirtyPaths: Set<string>): ArboristFileNode[] {
  return inputNodes.map((node) => {
    const children = mapNodes(node.children, dirtyPaths);
    const mappedNode: ArboristFileNode = {
      id: node.path,
      name: node.name,
      kind: node.type,
      path: node.path,
      isDirty: node.type === 'file' && dirtyPaths.has(node.path),
    };
    if (children.length > 0) {
      mappedNode.children = children;
    }
    return mappedNode;
  });
}

function searchMatch(node: NodeApi<ArboristFileNode>, searchTerm: string): boolean {
  return node.data.name.toLowerCase().includes(searchTerm.toLowerCase());
}

function getParentPath(path: string): string {
  const segments = path.split('/').filter((segment) => segment.length > 0);
  if (segments.length <= 1) {
    return '';
  }

  return segments.slice(0, -1).join('/');
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
      {node.data.isDirty && <span className="ide-tree-dirty-dot" aria-label="Unsaved changes" title="Unsaved changes" />}
    </div>
  );
}

export function FileTreePanel({
  nodes,
  selectedPath,
  dirtyPaths,
  searchQuery,
  onOpenFile,
  onRequestCreate,
  onRequestRename,
  onRequestDelete,
}: FileTreePanelProps): ReactElement {
  const mappedNodes = useMemo(() => mapNodes(nodes, dirtyPaths), [dirtyPaths, nodes]);
  const { ref, size } = useElementSize();
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);

  useEffect(() => {
    // Close custom tree context menu on global click/escape/scroll.
    const closeMenu = (): void => {
      setContextMenu(null);
    };

    const onEscape = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape') {
        return;
      }
      closeMenu();
    };

    window.addEventListener('click', closeMenu);
    window.addEventListener('scroll', closeMenu, true);
    window.addEventListener('keydown', onEscape);
    return () => {
      window.removeEventListener('click', closeMenu);
      window.removeEventListener('scroll', closeMenu, true);
      window.removeEventListener('keydown', onEscape);
    };
  }, []);

  const handleActivate = (node: NodeApi<ArboristFileNode>): void => {
    if (node.data.kind === 'file') {
      onOpenFile(node.data.path);
      return;
    }

    if (node.isOpen) {
      node.close();
      return;
    }

    node.open();
  };

  const handleContextMenuNode = (event: MouseEvent<HTMLDivElement>, node: NodeApi<ArboristFileNode>): void => {
    event.preventDefault();
    event.stopPropagation();
    setContextMenu({
      x: event.clientX,
      y: event.clientY,
      path: node.data.path,
      kind: node.data.kind,
    });
  };

  const handleContextMenuRoot = (event: MouseEvent<HTMLDivElement>): void => {
    event.preventDefault();
    setContextMenu({
      x: event.clientX,
      y: event.clientY,
      path: '',
      kind: 'directory',
    });
  };

  const handleCreate = (): void => {
    if (contextMenu == null) {
      return;
    }

    const targetPath = contextMenu.kind === 'directory'
      ? contextMenu.path
      : getParentPath(contextMenu.path);

    onRequestCreate(targetPath);
    setContextMenu(null);
  };

  const handleRename = (): void => {
    if (contextMenu == null || contextMenu.path.length === 0) {
      return;
    }

    onRequestRename(contextMenu.path);
    setContextMenu(null);
  };

  const handleDelete = (): void => {
    if (contextMenu == null || contextMenu.path.length === 0) {
      return;
    }

    onRequestDelete(contextMenu.path);
    setContextMenu(null);
  };

  return (
    <div className="ide-tree-panel h-100" ref={ref} aria-label="Project files" onContextMenu={handleContextMenuRoot}>
      <Tree<ArboristFileNode>
        data={mappedNodes}
        width={Math.max(220, size.width)}
        height={Math.max(180, size.height)}
        rowHeight={30}
        indent={18}
        openByDefault={false}
        selection={selectedPath ?? undefined}
        onActivate={handleActivate}
        disableDrag
        disableEdit
        searchTerm={searchQuery}
        searchMatch={searchMatch}
        renderRow={({ node, innerRef, attrs, children }) => (
          <div
            ref={innerRef}
            {...attrs}
            onContextMenu={(event) => handleContextMenuNode(event, node)}
          >
            {children}
          </div>
        )}
      >
        {FileTreeNodeRenderer}
      </Tree>

      {contextMenu != null && (
        <div
          className="ide-tree-context-menu"
          style={{ left: contextMenu.x, top: contextMenu.y }}
          role="menu"
          aria-label="File tree actions"
          onClick={(event) => event.stopPropagation()}
        >
          <button type="button" className="ide-tree-context-item" onClick={handleCreate} role="menuitem">
            New file
          </button>
          {contextMenu.path.length > 0 && (
            <>
              <button type="button" className="ide-tree-context-item" onClick={handleRename} role="menuitem">
                Rename
              </button>
              <button type="button" className="ide-tree-context-item danger" onClick={handleDelete} role="menuitem">
                Delete
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
