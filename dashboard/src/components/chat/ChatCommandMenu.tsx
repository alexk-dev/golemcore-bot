import type { CommandSuggestion } from './chatInputTypes';

interface ChatCommandMenuProps {
  menuId: string;
  suggestions: CommandSuggestion[];
  activeIndex: number;
  getOptionId: (index: number) => string;
  onSelect: (suggestion: CommandSuggestion) => void;
}

export function ChatCommandMenu({
  menuId,
  suggestions,
  activeIndex,
  getOptionId,
  onSelect,
}: ChatCommandMenuProps) {
  return (
    <div id={menuId} className="chat-command-menu" role="listbox" aria-label="Command suggestions">
      {suggestions.map((suggestion, index) => (
        <button
          id={getOptionId(index)}
          key={suggestion.key}
          type="button"
          className={`chat-command-item ${index === activeIndex ? 'active' : ''}`}
          role="option"
          tabIndex={-1}
          aria-selected={index === activeIndex}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => onSelect(suggestion)}
        >
          <span className="chat-command-name">{suggestion.label}</span>
          <span className="chat-command-desc">{suggestion.description}</span>
        </button>
      ))}
    </div>
  );
}
