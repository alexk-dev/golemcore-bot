import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactElement } from 'react';
import { Button, Form, InputGroup, ListGroup, Modal } from 'react-bootstrap';
import { FiSearch, FiStar } from 'react-icons/fi';
import type { QuickOpenItem } from '../../hooks/useIdeQuickOpen';

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
    <Modal show={show} onHide={onClose} centered>
      <Modal.Header closeButton>
        <Modal.Title>Quick Open</Modal.Title>
      </Modal.Header>
      <Modal.Body className="p-0">
        <div className="px-3 pt-3 pb-2 border-bottom">
          <InputGroup size="sm">
            <InputGroup.Text>
              <FiSearch size={14} />
            </InputGroup.Text>
            <Form.Control
              ref={inputRef}
              value={query}
              onChange={(event) => onQueryChange(event.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Type file name or path"
              aria-label="Quick open query"
            />
          </InputGroup>
          <div className="small text-body-secondary mt-2">
            ↑ ↓ navigate · Enter open · Space pin · Esc close
          </div>
        </div>

        <ListGroup variant="flush" className="ide-quick-open-list" role="listbox" aria-label="Quick open results">
          {items.length === 0 ? (
            <ListGroup.Item className="text-body-secondary">No matching files.</ListGroup.Item>
          ) : (
            items.map((item, index) => {
              const isActive = index === activeIndex;
              return (
                <ListGroup.Item
                  key={item.path}
                  action
                  active={isActive}
                  role="option"
                  aria-selected={isActive}
                  onMouseEnter={() => setActiveIndex(index)}
                  onClick={() => onPick(item.path)}
                  title={item.path}
                >
                  <div className="d-flex align-items-center justify-content-between gap-2">
                    <div className="fw-medium d-flex align-items-center gap-2">
                      {item.isPinned && <FiStar size={12} className="text-warning" />}
                      {item.title}
                    </div>
                    <div className="d-flex align-items-center gap-2">
                      {item.isRecent && <span className="badge text-bg-secondary">recent</span>}
                      <Button
                        variant="link"
                        size="sm"
                        className="p-0 ide-quick-open-pin"
                        aria-label={item.isPinned ? `Unpin ${item.title}` : `Pin ${item.title}`}
                        onClick={(event) => {
                          event.preventDefault();
                          event.stopPropagation();
                          onTogglePinned(item.path);
                        }}
                      >
                        <FiStar size={13} className={item.isPinned ? 'text-warning' : 'text-body-secondary'} />
                      </Button>
                    </div>
                  </div>
                  <div className="small text-body-secondary text-truncate">{item.path}</div>
                </ListGroup.Item>
              );
            })
          )}
        </ListGroup>
      </Modal.Body>
    </Modal>
  );
}
