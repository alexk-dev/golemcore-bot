import { useCallback, useEffect, type ReactElement } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Group, Panel, Separator, type PanelSize } from 'react-resizable-panels';
import { FiMessageSquare, FiTerminal } from 'react-icons/fi';
import ChatWindow from '../components/chat/ChatWindow';
import { ProposedEditsPanel } from '../components/ide/ProposedEditsPanel';
import { TerminalPane } from '../components/terminal/TerminalPane';
import { TerminalTabs } from '../components/terminal/TerminalTabs';
import { useTerminalStore } from '../store/terminalStore';
import { useWorkspaceLayoutStore } from '../store/workspaceLayoutStore';
import IdePage from './IdePage';

export default function WorkspacePage(): ReactElement {
  const chatSize = useWorkspaceLayoutStore((state) => state.chatSize);
  const terminalSize = useWorkspaceLayoutStore((state) => state.terminalSize);
  const isChatVisible = useWorkspaceLayoutStore((state) => state.isChatVisible);
  const isTerminalVisible = useWorkspaceLayoutStore((state) => state.isTerminalVisible);
  const toggleChat = useWorkspaceLayoutStore((state) => state.toggleChat);
  const toggleTerminal = useWorkspaceLayoutStore((state) => state.toggleTerminal);
  const setChatVisible = useWorkspaceLayoutStore((state) => state.setChatVisible);
  const setChatSize = useWorkspaceLayoutStore((state) => state.setChatSize);
  const setTerminalSize = useWorkspaceLayoutStore((state) => state.setTerminalSize);

  const tabs = useTerminalStore((state) => state.tabs);
  const activeTabId = useTerminalStore((state) => state.activeTabId);
  const openTab = useTerminalStore((state) => state.openTab);

  const handleChatResize = useCallback(
    (panelSize: PanelSize): void => {
      setChatSize(panelSize.asPercentage);
    },
    [setChatSize],
  );

  const handleTerminalResize = useCallback(
    (panelSize: PanelSize): void => {
      setTerminalSize(panelSize.asPercentage);
    },
    [setTerminalSize],
  );

  const [searchParams] = useSearchParams();
  const focus = searchParams.get('focus');

  // Sync stored chat visibility with ?focus= query param on mount / redirect landings.
  useEffect(() => {
    if (focus === 'chat') {
      setChatVisible(true);
    }
  }, [focus, setChatVisible]);

  // Global Ctrl+` shortcut toggles the integrated terminal panel.
  useEffect(() => {
    const handler = (event: KeyboardEvent): void => {
      if (event.ctrlKey && event.key === '`') {
        event.preventDefault();
        toggleTerminal();
      }
    };
    window.addEventListener('keydown', handler);
    return (): void => {
      window.removeEventListener('keydown', handler);
    };
  }, [toggleTerminal]);

  // Auto-open a first terminal tab when the terminal panel becomes visible.
  useEffect(() => {
    if (isTerminalVisible && tabs.length === 0) {
      openTab();
    }
  }, [isTerminalVisible, tabs.length, openTab]);

  return (
    <div className="workspace-page">
      <div className="workspace-toolbar" role="toolbar" aria-label="Workspace layout">
        <button
          type="button"
          data-testid="workspace-toggle-chat"
          className="workspace-toolbar-button"
          aria-pressed={isChatVisible}
          onClick={() => toggleChat()}
          title="Toggle chat panel"
        >
          <FiMessageSquare size={14} />
          <span>Chat</span>
        </button>
        <button
          type="button"
          data-testid="workspace-toggle-terminal"
          className="workspace-toolbar-button"
          aria-pressed={isTerminalVisible}
          onClick={() => toggleTerminal()}
          title="Toggle terminal panel"
        >
          <FiTerminal size={14} />
          <span>Terminal</span>
        </button>
      </div>
      <Group orientation="horizontal" className="workspace-main">
        <Panel defaultSize={`${100 - (isChatVisible ? chatSize : 0)}%`} minSize="30%">
          <Group orientation="vertical" className="workspace-center">
            <Panel defaultSize={`${100 - (isTerminalVisible ? terminalSize : 0)}%`} minSize="30%">
              <div className="workspace-editor-pane">
                <ProposedEditsPanel />
                <IdePage />
              </div>
            </Panel>
            {isTerminalVisible ? (
              <>
                <Separator className="workspace-resize-handle workspace-resize-handle-horizontal" />
                <Panel
                  defaultSize={`${terminalSize}%`}
                  minSize="10%"
                  maxSize="80%"
                  onResize={handleTerminalResize}
                >
                  <div className="workspace-terminal-pane">
                    <TerminalTabs />
                    <div className="workspace-terminal-body">
                      {activeTabId ? <TerminalPane key={activeTabId} tabId={activeTabId} /> : null}
                    </div>
                  </div>
                </Panel>
              </>
            ) : null}
          </Group>
        </Panel>
        {isChatVisible ? (
          <>
            <Separator className="workspace-resize-handle workspace-resize-handle-vertical" />
            <Panel
              defaultSize={`${chatSize}%`}
              minSize="15%"
              maxSize="60%"
              onResize={handleChatResize}
            >
              <div className="workspace-chat-pane">
                <ChatWindow embedded />
              </div>
            </Panel>
          </>
        ) : null}
      </Group>
    </div>
  );
}
