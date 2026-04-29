import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { FiSearch } from 'react-icons/fi';
import { useCommandPaletteStore } from '../../store/commandPaletteStore';
import { useChatSessionStore } from '../../store/chatSessionStore';
import { useInspectorStore } from '../../store/inspectorStore';
import { useCreateSession } from '../../hooks/useSessions';
import { createUuid } from '../../utils/uuid';
import { buildCommandRegistry, GROUP_LABELS, type CommandGroup } from './commandRegistry';
import { filterCommands, type ScoredCommand } from './fuzzyMatch';

function groupResults(results: ScoredCommand[]): Array<{ group: CommandGroup; entries: ScoredCommand[] }> {
  const groups = new Map<CommandGroup, ScoredCommand[]>();
  results.forEach((entry) => {
    const list = groups.get(entry.command.group) ?? [];
    list.push(entry);
    groups.set(entry.command.group, list);
  });
  return Array.from(groups.entries()).map(([group, entries]) => ({ group, entries }));
}

export default function CommandPalette() {
  const open = useCommandPaletteStore((s) => s.open);
  const togglePalette = useCommandPaletteStore((s) => s.togglePalette);
  const closePalette = useCommandPaletteStore((s) => s.closePalette);
  const navigate = useNavigate();
  const toggleInspector = useInspectorStore((s) => s.togglePanel);
  const setActiveSessionId = useChatSessionStore((s) => s.setActiveSessionId);
  const clientInstanceId = useChatSessionStore((s) => s.clientInstanceId);
  const createSession = useCreateSession();

  const [query, setQuery] = useState('');
  const [highlight, setHighlight] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const startNewSession = (): void => {
    const next = createUuid();
    setActiveSessionId(next);
    navigate('/');
    createSession.mutate({
      channelType: 'web',
      clientInstanceId,
      conversationKey: next,
      activate: true,
    });
  };

  const commands = useMemo(
    () => buildCommandRegistry({
      navigate,
      toggleInspector,
      togglePalette,
      startNewSession,
    }),
    // navigate, toggleInspector, togglePalette are stable; we accept the ref pattern.
    // startNewSession captures createSession.mutate which is stable per useMutation contract.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [navigate, toggleInspector, togglePalette, clientInstanceId],
  );

  const results = useMemo(() => filterCommands(commands, query), [commands, query]);
  const grouped = useMemo(() => groupResults(results), [results]);
  const flatResults = useMemo(() => grouped.flatMap((g) => g.entries), [grouped]);

  // Focus the search field every time the palette opens so the keyboard is ready immediately.
  useEffect(() => {
    if (open) {
      setQuery('');
      setHighlight(0);
      window.setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [open]);

  // Reset the highlight whenever the result list shrinks.
  useEffect(() => {
    if (highlight >= flatResults.length) {
      setHighlight(0);
    }
  }, [flatResults.length, highlight]);

  if (!open) {
    return null;
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>): void => {
    if (event.key === 'Escape') {
      event.preventDefault();
      closePalette();
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setHighlight((prev) => Math.min(prev + 1, Math.max(0, flatResults.length - 1)));
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setHighlight((prev) => Math.max(0, prev - 1));
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      const target = flatResults[highlight];
      if (target != null) {
        target.command.run();
      }
    }
  };

  return (
    <div className="command-palette" role="dialog" aria-modal="true" aria-label="Command palette">
      <button
        type="button"
        className="command-palette__backdrop"
        aria-label="Close command palette"
        onClick={closePalette}
      />
      <div className="command-palette__sheet">
        <div className="command-palette__search">
          <FiSearch size={16} aria-hidden="true" />
          <input
            ref={inputRef}
            type="text"
            placeholder="Type a command…"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={handleKeyDown}
            aria-label="Command search"
          />
          <span className="kbd" aria-hidden="true">esc</span>
        </div>
        {flatResults.length === 0 ? (
          <div className="command-palette__empty">No commands match “{query}”.</div>
        ) : (
          grouped.map(({ group, entries }) => (
            <div key={group} className="command-palette__group">
              <div className="command-palette__group-label">{GROUP_LABELS[group]}</div>
              {entries.map((entry) => {
                const flatIndex = flatResults.indexOf(entry);
                const isActive = flatIndex === highlight;
                return (
                  <button
                    key={entry.command.id}
                    type="button"
                    className={`command-palette__item${isActive ? ' command-palette__item--active' : ''}`}
                    onClick={() => entry.command.run()}
                    onMouseEnter={() => setHighlight(flatIndex)}
                  >
                    <span>{entry.command.label}</span>
                    {entry.command.shortcut != null && (
                      <span className="kbd" aria-hidden="true">{entry.command.shortcut}</span>
                    )}
                  </button>
                );
              })}
            </div>
          ))
        )}
      </div>
    </div>
  );
}
