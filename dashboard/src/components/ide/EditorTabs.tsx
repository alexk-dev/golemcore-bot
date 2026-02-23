import type { ReactElement } from 'react';
import { Badge, Button } from 'react-bootstrap';
import { FiX } from 'react-icons/fi';

export interface EditorTab {
  path: string;
  title: string;
  dirty: boolean;
}

export interface EditorTabsProps {
  tabs: EditorTab[];
  activePath: string | null;
  onSelectTab: (path: string) => void;
  onCloseTab: (path: string) => void;
}

function buildTabButtonId(path: string): string {
  const sanitized = path.replace(/[^a-zA-Z0-9-_]/g, '-');
  return `ide-tab-${sanitized}`;
}

export function EditorTabs({ tabs, activePath, onSelectTab, onCloseTab }: EditorTabsProps): ReactElement {
  return (
    <div className="ide-tabs" role="tablist" aria-label="Opened files">
      {tabs.map((tab) => {
        const isActive = tab.path === activePath;
        const tabButtonId = buildTabButtonId(tab.path);

        return (
          <div key={tab.path} className={`ide-tab ${isActive ? 'active' : ''}`} role="presentation">
            <button
              id={tabButtonId}
              type="button"
              className="ide-tab-main"
              role="tab"
              aria-selected={isActive}
              aria-controls="ide-editor-panel"
              onClick={() => onSelectTab(tab.path)}
              title={tab.path}
            >
              <span className="ide-tab-title">{tab.title}</span>
              {tab.dirty && <Badge bg="warning" text="dark" pill className="ide-tab-dirty">●</Badge>}
            </button>
            <Button
              type="button"
              variant="link"
              size="sm"
              className="ide-tab-close"
              onClick={() => onCloseTab(tab.path)}
              aria-label={`Close ${tab.title}`}
              title={`Close ${tab.title}`}
            >
              <FiX size={12} />
            </Button>
          </div>
        );
      })}
    </div>
  );
}
