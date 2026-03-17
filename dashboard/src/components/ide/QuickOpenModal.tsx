import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactElement } from 'react';
import { FiSearch, FiStar } from 'react-icons/fi';
import type { QuickOpenItem } from '../../hooks/useIdeQuickOpen';
import { Badge } from '../ui/badge';
import { Modal } from '../ui/bootstrap-overlay';
import { Input } from '../ui/field';
import { cn } from '../../lib/utils';

export interface QuickOpenModalProps {
  show: boolean;
  query: string;
  items: QuickOpenItem[];
  onClose: () => void;
  onQueryChange: (value: string) => void;
  onPick: (path: string) => void;
  onTogglePinned: (path: string) => void;
}

function clampIndex(nextIndex: number, max: number): number {
  if (max < 0) {
    return -1;
  }
  if (nextIndex < 0) {
    return 0;
  }
  if (nextIndex > max) {
    return max;
  }
  return nextIndex;
}

export function QuickOpenModal({
  show,
  query,
  items,
  onClose,
  onQueryChange,
  onPick,
  onTogglePinned,
}: QuickOpenModalProps): ReactElement {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [activeIndex, setActiveIndex] = useState<number>(0);

  useEffect(() => {
    // Reset active index when results change.
    setActiveIndex(0);
  }, [items]);

  useEffect(() => {
    // Auto focus query input when modal opens.
    if (!show) {
      return;
    }

    const timeoutId = window.setTimeout(() => {
      inputRef.current?.focus();
      inputRef.current?.select();
    }, 0);

    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [show]);

  const activeItem = useMemo(() => {
    if (items.length === 0) {
      return null;
    }
    return items[clampIndex(activeIndex, items.length - 1)] ?? null;
  }, [activeIndex, items]);

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>): void => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActiveIndex((current) => clampIndex(current + 1, items.length - 1));
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActiveIndex((current) => clampIndex(current - 1, items.length - 1));
      return;
    }

    if (event.key === 'Enter') {
      event.preventDefault();
      if (activeItem != null) {
        onPick(activeItem.path);
      }
      return;
    }

    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }

    if ((event.key === ' ' || event.key === 'Spacebar') && activeItem != null) {
      event.preventDefault();
      onTogglePinned(activeItem.path);
    }
  };

  return (
    <Modal show={show} onHide={onClose} centered size="lg">
      <Modal.Header closeButton>
        <Modal.Title>Quick Open</Modal.Title>
      </Modal.Header>
      <Modal.Body className="p-0">
        <div className="border-b border-border/80 px-5 pb-3 pt-5">
          <label className="input-with-leading-icon block">
            <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground">
              <FiSearch size={14} />
            </span>
            <Input
              ref={inputRef}
              className="h-10 rounded-xl border-border/80 bg-background/80 pr-3 text-sm shadow-none"
              value={query}
              onChange={(event) => onQueryChange(event.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Type file name or path"
              aria-label="Quick open query"
            />
          </label>
          <div className="mt-2 text-xs text-muted-foreground">
            ↑ ↓ navigate · Enter open · Space pin · Esc close
          </div>
        </div>

        <div className="ide-quick-open-list p-2" role="listbox" aria-label="Quick open results">
          {items.length === 0 ? (
            <div className="rounded-2xl px-4 py-8 text-sm text-muted-foreground">No matching files.</div>
          ) : (
            items.map((item, index) => {
              const isActive = index === activeIndex;
              return (
                <div
                  key={item.path}
                  role="option"
                  aria-selected={isActive}
                  className={cn(
                    'flex w-full items-start justify-between gap-3 rounded-2xl px-4 py-3 text-left transition-colors',
                    isActive ? 'bg-primary/12 text-foreground' : 'text-foreground hover:bg-muted/70'
                  )}
                  onMouseEnter={() => setActiveIndex(index)}
                  onClick={() => onPick(item.path)}
                  title={item.path}
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      {item.isPinned && <FiStar size={12} className="text-amber-500" />}
                      {item.title}
                    </div>
                    <div className="mt-1 truncate text-xs text-muted-foreground">{item.path}</div>
                  </div>
                  <div className="flex items-center gap-2">
                    {item.isRecent && <Badge variant="secondary">Recent</Badge>}
                    <button
                      type="button"
                      className="inline-flex h-8 w-8 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-background/80 hover:text-foreground"
                      aria-label={item.isPinned ? `Unpin ${item.title}` : `Pin ${item.title}`}
                      onClick={(event) => {
                        event.preventDefault();
                        event.stopPropagation();
                        onTogglePinned(item.path);
                      }}
                    >
                      <FiStar size={13} className={item.isPinned ? 'text-amber-500' : undefined} />
                    </button>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </Modal.Body>
    </Modal>
  );
}
