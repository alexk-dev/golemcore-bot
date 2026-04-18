import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useMediaQuery } from '../../hooks/useMediaQuery';
import { useTerminalStore, type TerminalTab } from '../../store/terminalStore';
import {
  useWorkspaceLayoutStore,
  type WorkspaceCompactPane,
} from '../../store/workspaceLayoutStore';

export interface WorkspaceFocusSyncOptions {
  focus: string | null;
  setChatVisible: (visible: boolean) => void;
  setCompactPane: (pane: WorkspaceCompactPane) => void;
  setCompactTerminalVisible: (visible: boolean) => void;
}

export interface WorkspaceTerminalShortcutOptions {
  isCompactLayout: boolean;
  toggleCompactTerminal: () => void;
  toggleTerminal: () => void;
}

export interface WorkspaceTerminalBootstrapOptions {
  isCompactLayout: boolean;
  isCompactTerminalVisible: boolean;
  isTerminalVisible: boolean;
  tabs: TerminalTab[];
  openTab: (cwd?: string) => string;
}

export interface WorkspacePageState {
  chatSize: number;
  terminalSize: number;
  isChatVisible: boolean;
  isTerminalVisible: boolean;
  compactActivePane: WorkspaceCompactPane;
  isCompactTerminalVisible: boolean;
  tabs: TerminalTab[];
  activeTabId: string | null;
  isCompactLayout: boolean;
  toggleChat: () => void;
  toggleTerminal: () => void;
  toggleCompactTerminal: () => void;
  setCompactPane: (pane: WorkspaceCompactPane) => void;
  setCompactTerminalVisible: (visible: boolean) => void;
  setChatSize: (size: number) => void;
  setTerminalSize: (size: number) => void;
}

export function useWorkspaceFocusSync({
  focus,
  setChatVisible,
  setCompactPane,
  setCompactTerminalVisible,
}: WorkspaceFocusSyncOptions): void {
  useEffect(() => {
    // Sync query-param deep links with the compact workspace pane that should open first.
    if (focus === 'chat') {
      setChatVisible(true);
      setCompactPane('chat');
      return;
    }
    if (focus === 'editor') {
      setCompactPane('editor');
      return;
    }
    if (focus === 'terminal') {
      setCompactTerminalVisible(true);
    }
  }, [focus, setChatVisible, setCompactPane, setCompactTerminalVisible]);
}

export function useWorkspaceTerminalShortcut({
  isCompactLayout,
  toggleCompactTerminal,
  toggleTerminal,
}: WorkspaceTerminalShortcutOptions): void {
  useEffect(() => {
    // Keep Ctrl+` mapped to the terminal surface that is actually visible for the current layout.
    const handler = (event: KeyboardEvent): void => {
      if (event.ctrlKey && event.key === '`') {
        event.preventDefault();
        if (isCompactLayout) {
          toggleCompactTerminal();
          return;
        }
        toggleTerminal();
      }
    };
    window.addEventListener('keydown', handler);
    return (): void => {
      window.removeEventListener('keydown', handler);
    };
  }, [isCompactLayout, toggleCompactTerminal, toggleTerminal]);
}

export function useWorkspaceTerminalBootstrap({
  isCompactLayout,
  isCompactTerminalVisible,
  isTerminalVisible,
  tabs,
  openTab,
}: WorkspaceTerminalBootstrapOptions): void {
  useEffect(() => {
    // Ensure the terminal overlay/split always has an initial tab the first time it opens.
    const shouldEnsureTerminalTab = isCompactLayout ? isCompactTerminalVisible : isTerminalVisible;
    if (shouldEnsureTerminalTab && tabs.length === 0) {
      openTab();
    }
  }, [isCompactLayout, isCompactTerminalVisible, isTerminalVisible, openTab, tabs.length]);
}

export function useWorkspacePageState(): WorkspacePageState {
  const chatSize = useWorkspaceLayoutStore((state) => state.chatSize);
  const terminalSize = useWorkspaceLayoutStore((state) => state.terminalSize);
  const isChatVisible = useWorkspaceLayoutStore((state) => state.isChatVisible);
  const isTerminalVisible = useWorkspaceLayoutStore((state) => state.isTerminalVisible);
  const compactActivePane = useWorkspaceLayoutStore((state) => state.compactActivePane);
  const isCompactTerminalVisible = useWorkspaceLayoutStore((state) => state.isCompactTerminalVisible);
  const toggleChat = useWorkspaceLayoutStore((state) => state.toggleChat);
  const toggleTerminal = useWorkspaceLayoutStore((state) => state.toggleTerminal);
  const toggleCompactTerminal = useWorkspaceLayoutStore((state) => state.toggleCompactTerminal);
  const setChatVisible = useWorkspaceLayoutStore((state) => state.setChatVisible);
  const setChatSize = useWorkspaceLayoutStore((state) => state.setChatSize);
  const setCompactPane = useWorkspaceLayoutStore((state) => state.setCompactPane);
  const setCompactTerminalVisible = useWorkspaceLayoutStore((state) => state.setCompactTerminalVisible);
  const setTerminalSize = useWorkspaceLayoutStore((state) => state.setTerminalSize);
  const tabs = useTerminalStore((state) => state.tabs);
  const activeTabId = useTerminalStore((state) => state.activeTabId);
  const openTab = useTerminalStore((state) => state.openTab);
  const isCompactLayout = useMediaQuery('(max-width: 991.98px)');
  const [searchParams] = useSearchParams();
  const focus = searchParams.get('focus');

  useWorkspaceFocusSync({ focus, setChatVisible, setCompactPane, setCompactTerminalVisible });
  useWorkspaceTerminalShortcut({ isCompactLayout, toggleCompactTerminal, toggleTerminal });
  useWorkspaceTerminalBootstrap({
    isCompactLayout,
    isCompactTerminalVisible,
    isTerminalVisible,
    tabs,
    openTab,
  });

  return {
    chatSize,
    terminalSize,
    isChatVisible,
    isTerminalVisible,
    compactActivePane,
    isCompactTerminalVisible,
    tabs,
    activeTabId,
    isCompactLayout,
    toggleChat,
    toggleTerminal,
    toggleCompactTerminal,
    setCompactPane,
    setCompactTerminalVisible,
    setChatSize,
    setTerminalSize,
  };
}
