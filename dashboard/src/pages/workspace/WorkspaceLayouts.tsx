import type { ReactElement } from 'react';
import { Group, Panel, Separator, type PanelSize } from 'react-resizable-panels';
import { FiCode, FiMessageSquare, FiMonitor, FiTerminal } from 'react-icons/fi';
import { Offcanvas } from '../../components/ui/overlay';
import ChatWindow from '../../components/chat/ChatWindow';
import { ProposedEditsPanel } from '../../components/ide/ProposedEditsPanel';
import { TerminalPane } from '../../components/terminal/TerminalPane';
import { TerminalTabs } from '../../components/terminal/TerminalTabs';
import type { TerminalTab } from '../../store/terminalStore';
import type { WorkspaceCompactPane } from '../../store/workspaceLayoutStore';
import IdePage from '../IdePage';

export interface CompactWorkspaceLayoutProps {
  activePane: WorkspaceCompactPane;
  isTerminalVisible: boolean;
  tabs: TerminalTab[];
  activeTabId: string | null;
  onSelectPane: (pane: WorkspaceCompactPane) => void;
  onToggleTerminal: () => void;
  onCloseTerminal: () => void;
}

export interface DesktopWorkspaceLayoutProps {
  chatSize: number;
  terminalSize: number;
  isChatVisible: boolean;
  isTerminalVisible: boolean;
  tabs: TerminalTab[];
  activeTabId: string | null;
  onToggleChat: () => void;
  onToggleTerminal: () => void;
  onChatResize: (panelSize: PanelSize) => void;
  onTerminalResize: (panelSize: PanelSize) => void;
}

interface CompactPaneToggleProps {
  activePane: WorkspaceCompactPane;
  pane: WorkspaceCompactPane;
  icon: ReactElement;
  label: string;
  onClick: () => void;
}

interface WorkspaceTerminalContentProps {
  tabs: TerminalTab[];
  activeTabId: string | null;
}

function CompactPaneToggle({
  activePane,
  pane,
  icon,
  label,
  onClick,
}: CompactPaneToggleProps): ReactElement {
  return (
    <button
      type="button"
      className="workspace-toolbar-button"
      aria-pressed={activePane === pane}
      data-testid={`workspace-compact-pane-${pane}`}
      onClick={onClick}
      title={`Show ${label.toLowerCase()} pane`}
    >
      {icon}
      <span>{label}</span>
    </button>
  );
}

function WorkspaceTerminalContent({ tabs, activeTabId }: WorkspaceTerminalContentProps): ReactElement {
  return (
    <div className="workspace-terminal-pane" data-testid="workspace-terminal-pane">
      <TerminalTabs />
      <div className="workspace-terminal-body">
        {tabs.map((tab) => {
          const isActive = tab.id === activeTabId;
          return (
            <div
              key={tab.id}
              className="workspace-terminal-session"
              data-testid={`workspace-terminal-session-${tab.id}`}
              hidden={!isActive}
              aria-hidden={!isActive}
            >
              <TerminalPane tabId={tab.id} cwd={tab.cwd} active={isActive} />
            </div>
          );
        })}
      </div>
    </div>
  );
}

function WorkspaceEditorShell(): ReactElement {
  return (
    <div className="workspace-editor-pane" data-testid="workspace-editor-pane">
      <ProposedEditsPanel />
      <IdePage />
    </div>
  );
}

function WorkspaceChatShell(): ReactElement {
  return (
    <div className="workspace-chat-pane" data-testid="workspace-chat-pane">
      <ChatWindow embedded />
    </div>
  );
}

export function CompactWorkspaceLayout({
  activePane,
  isTerminalVisible,
  tabs,
  activeTabId,
  onSelectPane,
  onToggleTerminal,
  onCloseTerminal,
}: CompactWorkspaceLayoutProps): ReactElement {
  return (
    <div className="workspace-page workspace-page--compact" data-testid="workspace-page-compact">
      <div className="workspace-toolbar" role="toolbar" aria-label="Workspace layout">
        <div className="workspace-toolbar-group" role="group" aria-label="Focused workspace pane">
          <CompactPaneToggle
            activePane={activePane}
            pane="editor"
            icon={<FiCode size={14} />}
            label="Editor"
            onClick={() => onSelectPane('editor')}
          />
          <CompactPaneToggle
            activePane={activePane}
            pane="chat"
            icon={<FiMessageSquare size={14} />}
            label="Chat"
            onClick={() => onSelectPane('chat')}
          />
        </div>
        <button
          type="button"
          data-testid="workspace-toggle-terminal"
          className="workspace-toolbar-button"
          aria-pressed={isTerminalVisible}
          onClick={onToggleTerminal}
          title="Toggle terminal panel"
        >
          <FiTerminal size={14} />
          <span>Terminal</span>
        </button>
      </div>

      <div className="workspace-compact-main" data-testid="workspace-compact-main">
        {activePane === 'chat' ? <WorkspaceChatShell /> : <WorkspaceEditorShell />}
      </div>

      <Offcanvas
        show={isTerminalVisible}
        onHide={onCloseTerminal}
        placement="bottom"
        className="workspace-terminal-offcanvas"
      >
        <Offcanvas.Header closeButton>
          <Offcanvas.Title>Terminal</Offcanvas.Title>
        </Offcanvas.Header>
        <Offcanvas.Body className="workspace-terminal-offcanvas-body p-0">
          <WorkspaceTerminalContent tabs={tabs} activeTabId={activeTabId} />
        </Offcanvas.Body>
      </Offcanvas>
    </div>
  );
}

export function DesktopWorkspaceLayout({
  chatSize,
  terminalSize,
  isChatVisible,
  isTerminalVisible,
  tabs,
  activeTabId,
  onToggleChat,
  onToggleTerminal,
  onChatResize,
  onTerminalResize,
}: DesktopWorkspaceLayoutProps): ReactElement {
  return (
    <div className="workspace-page" data-testid="workspace-page-desktop">
      <div className="workspace-toolbar" role="toolbar" aria-label="Workspace layout">
        <button
          type="button"
          data-testid="workspace-toggle-chat"
          className="workspace-toolbar-button"
          aria-pressed={isChatVisible}
          onClick={onToggleChat}
          title="Toggle chat panel"
        >
          <FiMonitor size={14} />
          <span>Split Chat</span>
        </button>
        <button
          type="button"
          data-testid="workspace-toggle-terminal"
          className="workspace-toolbar-button"
          aria-pressed={isTerminalVisible}
          onClick={onToggleTerminal}
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
              <WorkspaceEditorShell />
            </Panel>
            {isTerminalVisible ? (
              <>
                <Separator className="workspace-resize-handle workspace-resize-handle-horizontal" />
                <Panel
                  defaultSize={`${terminalSize}%`}
                  minSize="10%"
                  maxSize="80%"
                  onResize={onTerminalResize}
                >
                  <WorkspaceTerminalContent tabs={tabs} activeTabId={activeTabId} />
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
              onResize={onChatResize}
            >
              <WorkspaceChatShell />
            </Panel>
          </>
        ) : null}
      </Group>
    </div>
  );
}
