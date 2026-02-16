import { CommandSpec } from '../../api/commands';

interface Props {
  commands: CommandSpec[];
  input: string;
  selectedIndex: number;
  visible: boolean;
  listboxId?: string;
}

export function getCommandQuery(input: string): string {
  if (!input.startsWith('/')) return '';
  const firstToken = input.slice(1).split(/\s+/)[0] ?? '';
  return firstToken.toLowerCase();
}

export function filterCommands(commands: CommandSpec[], input: string): CommandSpec[] {
  const q = getCommandQuery(input);
  if (!q) return commands;
  return commands.filter((c) => c.name.toLowerCase().startsWith(q));
}

export default function CommandAutocomplete({
  commands,
  input,
  selectedIndex,
  visible,
  listboxId = 'command-listbox',
}: Props) {
  if (!visible) return null;
  const filtered = filterCommands(commands, input).slice(0, 8);
  if (filtered.length === 0) return null;

  return (
    <div className="command-autocomplete" role="listbox" id={listboxId} aria-label="Command suggestions">
      {filtered.map((cmd, i) => (
        <div
          key={cmd.name}
          id={`${listboxId}-option-${i}`}
          role="option"
          aria-selected={i === selectedIndex}
          className={`command-item ${i === selectedIndex ? 'active' : ''}`}
        >
          <div className="command-name">/{cmd.name}</div>
          <div className="command-desc">{cmd.description}</div>
          <div className="command-usage">{cmd.usage}</div>
        </div>
      ))}
    </div>
  );
}
