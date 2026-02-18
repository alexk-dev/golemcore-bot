import type { CommandSuggestion } from './chatInputTypes';

interface ChatCommandMenuProps {
  suggestions: CommandSuggestion[];
  activeIndex: number;
  onSelect: (suggestion: CommandSuggestion) => void;
}

export function ChatCommandMenu({ suggestions, activeIndex, onSelect }: ChatCommandMenuProps) {
  return (
    <div className="chat-command-menu">
      {suggestions.map((suggestion, index) => (
        <button
          key={suggestion.key}
          type="button"
          className={`chat-command-item ${index === activeIndex ? 'active' : ''}`}
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
