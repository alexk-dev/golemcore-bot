import type { CommandDefinition } from './commandRegistry';

export interface ScoredCommand {
  command: CommandDefinition;
  score: number;
}

function scoreLabel(label: string, query: string): number {
  if (query.length === 0) {
    return 1;
  }
  const lowerLabel = label.toLowerCase();
  const lowerQuery = query.toLowerCase();
  if (lowerLabel === lowerQuery) {
    return 1000;
  }
  if (lowerLabel.startsWith(lowerQuery)) {
    return 800;
  }
  if (lowerLabel.includes(lowerQuery)) {
    return 600;
  }
  // Subsequence bonus: every query char must appear in order in the label.
  let labelIndex = 0;
  let matched = 0;
  for (const ch of lowerQuery) {
    const found = lowerLabel.indexOf(ch, labelIndex);
    if (found === -1) {
      return 0;
    }
    matched += 1;
    labelIndex = found + 1;
  }
  return 100 + matched * 5;
}

export function filterCommands(commands: CommandDefinition[], query: string): ScoredCommand[] {
  const trimmed = query.trim();
  return commands
    .map((command) => ({ command, score: scoreLabel(command.label, trimmed) }))
    .filter((entry) => entry.score > 0)
    .sort((a, b) => b.score - a.score);
}
