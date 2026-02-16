import { describe, expect, it } from 'vitest';
import { filterCommands, getCommandQuery } from './CommandAutocomplete';
import type { CommandSpec } from '../../api/commands';

const COMMANDS: CommandSpec[] = [
  { name: 'plan', description: 'Plan mode', usage: '/plan <on|off|status>' },
  { name: 'help', description: 'Help', usage: '/help' },
  { name: 'status', description: 'Status', usage: '/status' },
];

describe('CommandAutocomplete helpers', () => {
  it('extracts command query from slash input', () => {
    expect(getCommandQuery('/pla')).toBe('pla');
    expect(getCommandQuery('/plan on')).toBe('plan');
    expect(getCommandQuery('plan')).toBe('');
  });

  it('filters by prefix', () => {
    expect(filterCommands(COMMANDS, '/p').map((c) => c.name)).toEqual(['plan']);
    expect(filterCommands(COMMANDS, '/s').map((c) => c.name)).toEqual(['status']);
    expect(filterCommands(COMMANDS, '/').length).toBe(3);
  });
});
