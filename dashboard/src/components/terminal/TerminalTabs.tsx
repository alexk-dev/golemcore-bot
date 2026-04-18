import type { ReactElement } from 'react';
import { FiPlus, FiX } from 'react-icons/fi';
import { useTerminalStore } from '../../store/terminalStore';

/**
 * Tab strip for the embedded workspace terminal sessions.
 */
export function TerminalTabs(): ReactElement {
  const tabs = useTerminalStore((state) => state.tabs);
  const activeTabId = useTerminalStore((state) => state.activeTabId);
  const openTab = useTerminalStore((state) => state.openTab);
  const closeTab = useTerminalStore((state) => state.closeTab);
  const setActiveTab = useTerminalStore((state) => state.setActiveTab);

  const handleNewTab = (): void => {
    openTab();
  };

  return (
    <div className="terminal-tabs" role="tablist" aria-label="Terminal tabs">
      {tabs.map((tab) => {
        const isActive = tab.id === activeTabId;
        return (
          <div
            key={tab.id}
            className={`terminal-tab${isActive ? ' terminal-tab--active' : ''}`}
          >
            <button
              type="button"
              role="tab"
              aria-selected={isActive}
              data-testid={`terminal-tab-${tab.id}`}
              className="terminal-tab-label"
              onClick={() => setActiveTab(tab.id)}
            >
              {tab.title}
            </button>
            <button
              type="button"
              aria-label={`Close ${tab.title}`}
              data-testid={`terminal-tab-close-${tab.id}`}
              className="terminal-tab-close"
              onClick={() => closeTab(tab.id)}
            >
              <FiX size={12} />
            </button>
          </div>
        );
      })}
      <button
        type="button"
        aria-label="New terminal tab"
        data-testid="terminal-new-tab"
        className="terminal-tab-new"
        onClick={handleNewTab}
      >
        <FiPlus size={12} />
      </button>
    </div>
  );
}
