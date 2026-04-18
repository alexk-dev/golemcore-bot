import { useCallback, type ReactElement } from 'react';
import type { PanelSize } from 'react-resizable-panels';
import { DesktopWorkspaceLayout, CompactWorkspaceLayout } from './workspace/WorkspaceLayouts';
import { useWorkspacePageState } from './workspace/useWorkspacePageState';

export default function WorkspacePage(): ReactElement {
  const {
    activeTabId,
    chatSize,
    compactActivePane,
    isChatVisible,
    isCompactLayout,
    isCompactTerminalVisible,
    isTerminalVisible,
    setChatSize,
    setCompactPane,
    setCompactTerminalVisible,
    setTerminalSize,
    tabs,
    terminalSize,
    toggleChat,
    toggleCompactTerminal,
    toggleTerminal,
  } = useWorkspacePageState();

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

  if (isCompactLayout) {
    return (
      <CompactWorkspaceLayout
        activePane={compactActivePane}
        isTerminalVisible={isCompactTerminalVisible}
        tabs={tabs}
        activeTabId={activeTabId}
        onSelectPane={setCompactPane}
        onToggleTerminal={toggleCompactTerminal}
        onCloseTerminal={() => setCompactTerminalVisible(false)}
      />
    );
  }

  return (
    <DesktopWorkspaceLayout
      chatSize={chatSize}
      terminalSize={terminalSize}
      isChatVisible={isChatVisible}
      isTerminalVisible={isTerminalVisible}
      tabs={tabs}
      activeTabId={activeTabId}
      onToggleChat={toggleChat}
      onToggleTerminal={toggleTerminal}
      onChatResize={handleChatResize}
      onTerminalResize={handleTerminalResize}
    />
  );
}
