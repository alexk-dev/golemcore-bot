import type { NavigateFunction } from 'react-router-dom';

export type CommandGroup = 'navigation' | 'agent' | 'view' | 'system';

export interface CommandDefinition {
  id: string;
  label: string;
  description?: string;
  group: CommandGroup;
  shortcut?: string;
  run: () => void;
}

export interface CommandContext {
  navigate: NavigateFunction;
  toggleInspector: () => void;
  togglePalette: () => void;
  startNewSession: () => void;
}

export const GROUP_LABELS: Record<CommandGroup, string> = {
  navigation: 'Navigation',
  agent: 'Agent',
  view: 'View',
  system: 'System',
};

export function buildCommandRegistry(ctx: CommandContext): CommandDefinition[] {
  return [
    {
      id: 'chat.new',
      label: 'New chat',
      description: 'Start a fresh chat session',
      group: 'agent',
      shortcut: '⌘N',
      run: () => {
        ctx.startNewSession();
        ctx.togglePalette();
      },
    },
    {
      id: 'nav.chat',
      label: 'Open chat',
      group: 'navigation',
      run: () => { ctx.navigate('/'); ctx.togglePalette(); },
    },
    {
      id: 'nav.sessions',
      label: 'Search sessions',
      group: 'navigation',
      run: () => { ctx.navigate('/sessions'); ctx.togglePalette(); },
    },
    {
      id: 'nav.logs',
      label: 'Open logs',
      group: 'navigation',
      run: () => { ctx.navigate('/logs'); ctx.togglePalette(); },
    },
    {
      id: 'nav.diagnostics',
      label: 'Open diagnostics',
      group: 'navigation',
      run: () => { ctx.navigate('/diagnostics'); ctx.togglePalette(); },
    },
    {
      id: 'nav.scheduler',
      label: 'Open scheduler',
      group: 'navigation',
      run: () => { ctx.navigate('/scheduler'); ctx.togglePalette(); },
    },
    {
      id: 'nav.skills',
      label: 'Open skills',
      group: 'navigation',
      run: () => { ctx.navigate('/skills'); ctx.togglePalette(); },
    },
    {
      id: 'nav.prompts',
      label: 'Open prompts',
      group: 'navigation',
      run: () => { ctx.navigate('/prompts'); ctx.togglePalette(); },
    },
    {
      id: 'nav.settings',
      label: 'Open settings',
      group: 'navigation',
      run: () => { ctx.navigate('/settings'); ctx.togglePalette(); },
    },
    {
      id: 'nav.workspace',
      label: 'Open workspace',
      group: 'navigation',
      run: () => { ctx.navigate('/workspace'); ctx.togglePalette(); },
    },
    {
      id: 'view.toggleInspector',
      label: 'Toggle inspector panel',
      group: 'view',
      shortcut: '⌘I',
      run: () => { ctx.toggleInspector(); ctx.togglePalette(); },
    },
    {
      id: 'system.scheduler.create',
      label: 'Create scheduler task',
      group: 'system',
      run: () => { ctx.navigate('/scheduler?create=1'); ctx.togglePalette(); },
    },
    {
      id: 'system.exportSession',
      label: 'Export session as JSON',
      group: 'system',
      run: () => {
        ctx.togglePalette();
        window.dispatchEvent(new CustomEvent('harness:export-session'));
      },
    },
  ];
}
