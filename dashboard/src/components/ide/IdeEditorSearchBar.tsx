import type { ReactElement } from 'react';
import { FiSearch, FiX } from 'react-icons/fi';
import { Input } from '../ui/field';
import { Button } from '../ui/button';

export interface IdeEditorSearchBarProps {
  show: boolean;
  query: string;
  onQueryChange: (query: string) => void;
  onClose: () => void;
}

export function IdeEditorSearchBar({ show, query, onQueryChange, onClose }: IdeEditorSearchBarProps): ReactElement | null {
  if (!show) {
    return null;
  }

  return (
    <div className="flex items-center gap-2 border-b border-border/80 bg-background/70 px-4 py-2">
      <FiSearch size={14} className="text-muted-foreground" />
      <Input
        className="h-8 rounded-lg border-border/80 bg-background text-sm shadow-none"
        value={query}
        onChange={(event) => onQueryChange(event.target.value)}
        placeholder="Search in current file"
        aria-label="Search in current file"
        autoFocus
      />
      <Button size="sm" variant="secondary" onClick={onClose} aria-label="Close editor search">
        <FiX size={14} />
      </Button>
    </div>
  );
}
