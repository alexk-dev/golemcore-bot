import { describe, expect, it } from 'vitest';
import { filterCommands } from './fuzzyMatch';
import type { CommandDefinition } from './commandRegistry';

const commands: CommandDefinition[] = [
  { id: 'a', label: 'New chat', group: 'agent', run: () => undefined },
  { id: 'b', label: 'Open logs', group: 'navigation', run: () => undefined },
  { id: 'c', label: 'Open diagnostics', group: 'navigation', run: () => undefined },
  { id: 'd', label: 'Open settings', group: 'navigation', run: () => undefined },
];

describe('filterCommands', () => {
  it('returns all commands when query is empty', () => {
    expect(filterCommands(commands, '')).toHaveLength(commands.length);
  });

  it('prefers prefix matches over subsequence matches', () => {
    const ranked = filterCommands(commands, 'open');
    expect(ranked.map((entry) => entry.command.id).slice(0, 3)).toEqual(['b', 'c', 'd']);
  });

  it('returns subsequence matches when no direct match', () => {
    const ranked = filterCommands(commands, 'os');
    expect(ranked.find((entry) => entry.command.id === 'b')).toBeDefined();
  });

  it('returns no match when characters cannot align', () => {
    expect(filterCommands(commands, 'xyz')).toHaveLength(0);
  });
});
