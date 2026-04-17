import { useTerminalStore } from '../../store/terminalStore';
import { useWorkspaceLayoutStore } from '../../store/workspaceLayoutStore';

const RUN_PREFIX = '/run';

/**
 * Extracts the shell command from a chat `/run ...` instruction.
 */
export function parseRunCommand(text: string): string | null {
  const trimmedStart = text.trimStart();
  if (!trimmedStart.startsWith(RUN_PREFIX)) {
    return null;
  }
  const remainder = trimmedStart.slice(RUN_PREFIX.length);
  if (remainder.length === 0 || (!remainder.startsWith(' ') && !remainder.startsWith('\t'))) {
    return null;
  }
  const command = remainder.trim();
  return command.length > 0 ? command : null;
}

/**
 * Routes a parsed `/run` command into the active workspace terminal tab.
 */
export function routeRunCommandToTerminal(command: string): void {
  const terminal = useTerminalStore.getState();
  const targetTabId = terminal.activeTabId ?? terminal.openTab();
  useTerminalStore.getState().enqueueInput(targetTabId, `${command}\n`);
  useWorkspaceLayoutStore.getState().setTerminalVisible(true);
}
