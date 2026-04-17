import type { ReactElement } from 'react';
import type { MentionFileEntry } from './fileMentions';

export interface FileMentionMenuProps {
  suggestions: MentionFileEntry[];
  onSelect: (path: string) => void;
}

/**
 * Small suggestion menu shown when the user inserts file mentions in chat.
 */
export function FileMentionMenu({ suggestions, onSelect }: FileMentionMenuProps): ReactElement {
  if (suggestions.length === 0) {
    return (
      <div className="file-mention-menu" role="listbox" aria-label="File mentions">
        <div data-testid="file-mention-empty" className="file-mention-empty">
          No matching files
        </div>
      </div>
    );
  }

  return (
    <div className="file-mention-menu" role="listbox" aria-label="File mentions">
      {suggestions.map((entry, index) => (
        <button
          key={entry.path}
          type="button"
          role="option"
          aria-selected={false}
          data-testid={`file-mention-option-${index}`}
          className="file-mention-option"
          onClick={() => onSelect(entry.path)}
        >
          <span className="file-mention-name">{entry.name}</span>
          <span className="file-mention-path">{entry.path}</span>
        </button>
      ))}
    </div>
  );
}
