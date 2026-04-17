/* @vitest-environment jsdom */

import { act, type ReactElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FileTreeNode } from '../../api/files';
import { FileTreePanel } from './FileTreePanel';

vi.mock('../../hooks/useElementSize', () => ({
  useElementSize: () => ({
    ref: vi.fn(),
    size: { width: 320, height: 480 },
  }),
}));

vi.mock('react-arborist', () => ({
  Tree: ({
    data,
    children: NodeRenderer,
    renderRow,
  }: {
    data: Array<Record<string, unknown> & { children?: Array<Record<string, unknown>> }>;
    children: (props: Record<string, unknown>) => ReactElement;
    renderRow: (props: Record<string, unknown>) => ReactElement;
  }) => {
    const rows: ReactElement[] = [];
    const walk = (items: Array<Record<string, unknown> & { children?: Array<Record<string, unknown>> }>): void => {
      for (const item of items) {
        const node = {
          data: item,
          isSelected: false,
          isOpen: false,
          open: vi.fn(),
          close: vi.fn(),
          handleClick: vi.fn(),
        };
        rows.push(
          <div key={String(item.id)} data-testid={`mock-tree-row-${String(item.path)}`}>
            {renderRow({
              node,
              innerRef: vi.fn(),
              attrs: {},
              children: <NodeRenderer node={node} style={{}} dragHandle={vi.fn()} />,
            })}
          </div>,
        );
        if (item.children != null) {
          walk(item.children);
        }
      }
    };
    walk(data);
    return <div>{rows}</div>;
  },
}));

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

interface Harness {
  container: HTMLDivElement;
  root: Root;
}

function createNode(
  path: string,
  name: string,
  type: 'file' | 'directory',
  children: FileTreeNode[] = [],
): FileTreeNode {
  return {
    path,
    name,
    type,
    binary: false,
    image: false,
    editable: type === 'file',
    hasChildren: children.length > 0,
    children,
  };
}

function mount(overrides: Partial<Parameters<typeof FileTreePanel>[0]> = {}): Harness {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  const props: Parameters<typeof FileTreePanel>[0] = {
    nodes: [
      createNode('src', 'src', 'directory', [
        createNode('src/App.tsx', 'App.tsx', 'file'),
      ]),
      createNode('README.md', 'README.md', 'file'),
    ],
    selectedPath: null,
    dirtyPaths: new Set(),
    searchQuery: '',
    onOpenFile: vi.fn(),
    onLoadDirectory: vi.fn(),
    onRequestCreate: vi.fn(),
    onRequestRename: vi.fn(),
    onRequestDelete: vi.fn(),
    onOpenTerminalHere: vi.fn(),
    ...overrides,
  };

  act(() => {
    root.render(<FileTreePanel {...props} />);
  });
  return { container, root };
}

function unmount({ container, root }: Harness): void {
  act(() => {
    root.unmount();
  });
  container.remove();
}

function getRow(container: HTMLDivElement, path: string): HTMLDivElement {
  const row = container.querySelector<HTMLDivElement>(`[data-testid="mock-tree-row-${path}"]`);
  if (row == null) {
    throw new Error(`Row ${path} not found`);
  }
  const renderedNode = row.querySelector<HTMLDivElement>('.ide-tree-node');
  if (renderedNode == null) {
    throw new Error(`Rendered node ${path} not found`);
  }
  return renderedNode;
}

function findMenuItem(label: string): HTMLButtonElement | null {
  return Array.from(document.querySelectorAll<HTMLButtonElement>('button[role="menuitem"]'))
    .find((button) => button.textContent === label) ?? null;
}

describe('FileTreePanel', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('opens a terminal from a directory context menu', () => {
    const onOpenTerminalHere = vi.fn();
    const harness = mount({ onOpenTerminalHere });

    act(() => {
      getRow(harness.container, 'src').dispatchEvent(new MouseEvent('contextmenu', { bubbles: true }));
    });
    act(() => {
      findMenuItem('Open terminal here')?.click();
    });

    expect(onOpenTerminalHere).toHaveBeenCalledWith('src');

    unmount(harness);
  });

  it('does not expose terminal action for files', () => {
    const onOpenTerminalHere = vi.fn();
    const harness = mount({ onOpenTerminalHere });

    act(() => {
      getRow(harness.container, 'README.md').dispatchEvent(new MouseEvent('contextmenu', { bubbles: true }));
    });

    expect(findMenuItem('Open terminal here')).toBeNull();
    expect(onOpenTerminalHere).not.toHaveBeenCalled();

    unmount(harness);
  });
});
