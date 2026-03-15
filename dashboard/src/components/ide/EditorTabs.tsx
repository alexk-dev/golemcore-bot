import type { ReactElement } from 'react';
import { FiX } from 'react-icons/fi';
import { cn } from '../../lib/utils';

export interface EditorTab {
  path: string;
  title: string;
  context: string | null;
  fullTitle: string;
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
        const closeLabel = `Close ${tab.fullTitle}`;

        return (
          <div
            key={tab.path}
            className={cn('ide-tab', isActive && 'active')}
            role="presentation"
          >
            <button
              id={tabButtonId}
              type="button"
              className="ide-tab-main"
              role="tab"
              aria-selected={isActive}
              aria-controls="ide-editor-panel"
              onClick={() => onSelectTab(tab.path)}
              title={tab.fullTitle}
            >
              <span className="ide-tab-label">
                <span className="ide-tab-title">{tab.title}</span>
                {tab.context != null && <span className="ide-tab-context">· {tab.context}</span>}
              </span>
              {tab.dirty && <span className="ide-tab-dirty" aria-label="Unsaved changes" />}
            </button>
            <button
              type="button"
              className="ide-tab-close"
              onClick={() => onCloseTab(tab.path)}
              aria-label={closeLabel}
              title={closeLabel}
            >
              <FiX size={12} />
            </button>
          </div>
        );
      })}
    </div>
  );
}
