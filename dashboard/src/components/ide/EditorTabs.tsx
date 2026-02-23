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

export function EditorTabs({ tabs, activePath, onSelectTab, onCloseTab }: EditorTabsProps): ReactElement {
  return (
    <div className="ide-tabs">
      {tabs.map((tab) => {
        const isActive = tab.path === activePath;
        return (
          <button
            key={tab.path}
            type="button"
            className={`ide-tab ${isActive ? 'active' : ''}`}
            onClick={() => onSelectTab(tab.path)}
          >
            <span className="ide-tab-title">{tab.title}</span>
            {tab.dirty && <Badge bg="warning" text="dark" pill className="ide-tab-dirty">●</Badge>}
            <Button
              type="button"
              variant="link"
              size="sm"
              className="ide-tab-close"
              onClick={(event) => {
                event.stopPropagation();
                onCloseTab(tab.path);
              }}
              aria-label={`Close ${tab.title}`}
            >
              <FiX size={12} />
            </Button>
          </button>
        );
      })}
    </div>
  );
}
