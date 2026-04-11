import { type KeyboardEvent, type ReactElement, useEffect, useId, useMemo, useRef, useState } from 'react';
import { FiCheck, FiChevronDown } from 'react-icons/fi';
import { cn } from '../../lib/utils';

export interface AutocompleteComboboxProps {
  value: string;
  suggestions: string[];
  placeholder?: string;
  disabled?: boolean;
  hasError?: boolean;
  emptyState?: string;
  onValueChange: (value: string) => void;
  onSubmit?: () => void;
}


interface ComboboxMenuProps {
  listboxId: string;
  activeIndex: number;
  selectedSuggestion: string;
  filteredSuggestions: string[];
  emptyState?: string;
  onSelectSuggestion: (value: string) => void;
}

interface ComboboxTriggerProps {
  disabled: boolean;
  showMenu: boolean;
  onToggleMenu: () => void;
}

type KeyHandler = (event: KeyboardEvent<HTMLInputElement>) => boolean;

function wrapSuggestionIndex(currentIndex: number, suggestionCount: number, direction: 1 | -1): number {
  if (suggestionCount === 0) {
    return -1;
  }
  if (currentIndex < 0) {
    return direction === 1 ? 0 : suggestionCount - 1;
  }
  return (currentIndex + direction + suggestionCount) % suggestionCount;
}

function normalizeSuggestions(suggestions: string[], value: string): string[] {
  const loweredValue = value.trim().toLowerCase();
  const uniqueSuggestions = Array.from(new Set(suggestions));
  return uniqueSuggestions
    .filter((suggestion) => suggestion.toLowerCase().includes(loweredValue))
    .sort((left, right) => left.localeCompare(right));
}

export function AutocompleteCombobox({
  value,
  suggestions,
  placeholder,
  disabled = false,
  hasError = false,
  emptyState,
  onValueChange,
  onSubmit,
}: AutocompleteComboboxProps): ReactElement {
  const listboxId = useId();
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [isOpen, setIsOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState<number>(-1);

  const filteredSuggestions = useMemo(() => normalizeSuggestions(suggestions, value), [suggestions, value]);
  const activeSuggestion = activeIndex >= 0 ? filteredSuggestions[activeIndex] : null;
  const selectedSuggestion = value.trim();
  const showMenu = isOpen && (filteredSuggestions.length > 0 || emptyState != null);

  useEffect(() => {
    if (filteredSuggestions.length === 0) {
      setActiveIndex(-1);
      return;
    }
    if (activeIndex >= filteredSuggestions.length) {
      setActiveIndex(0);
    }
  }, [activeIndex, filteredSuggestions]);

  useEffect(() => {
    function handlePointerDown(event: MouseEvent): void {
      if (wrapperRef.current == null) {
        return;
      }
      if (event.target instanceof Node && !wrapperRef.current.contains(event.target)) {
        setIsOpen(false);
      }
    }

    document.addEventListener('mousedown', handlePointerDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
    };
  }, []);

  function handleSelectSuggestion(nextValue: string): void {
    onValueChange(nextValue);
    setIsOpen(false);
    setActiveIndex(-1);
    inputRef.current?.focus();
  }

  function handleToggleMenu(): void {
    if (disabled) {
      return;
    }

    inputRef.current?.focus();
    setIsOpen((current) => {
      if (current) {
        setActiveIndex(-1);
        return false;
      }
      setActiveIndex(filteredSuggestions.length > 0 ? 0 : -1);
      return true;
    });
  }

  const keyHandlers: Record<string, KeyHandler> = {
    ArrowDown: (event) => {
      event.preventDefault();
      if (!isOpen) {
        setIsOpen(true);
        setActiveIndex(filteredSuggestions.length > 0 ? 0 : -1);
        return true;
      }
      setActiveIndex((currentIndex) => wrapSuggestionIndex(currentIndex, filteredSuggestions.length, 1));
      return true;
    },
    ArrowUp: (event) => {
      event.preventDefault();
      if (!isOpen) {
        setIsOpen(true);
        setActiveIndex(filteredSuggestions.length > 0 ? 0 : -1);
        return true;
      }
      setActiveIndex((currentIndex) => wrapSuggestionIndex(currentIndex, filteredSuggestions.length, -1));
      return true;
    },
    Enter: (event) => {
      event.preventDefault();
      if (isOpen && activeSuggestion != null) {
        handleSelectSuggestion(activeSuggestion);
      } else {
        onSubmit?.();
      }
      return true;
    },
    Escape: (event) => {
      if (!isOpen) {
        return false;
      }
      event.preventDefault();
      setIsOpen(false);
      setActiveIndex(-1);
      return true;
    },
  };

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>): void {
    const handler = keyHandlers[event.key];
    if (handler == null) {
      return;
    }
    if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && filteredSuggestions.length === 0 && emptyState == null) {
      return;
    }
    handler(event);
  }

  return (
    <div ref={wrapperRef} className="autocomplete-combobox relative min-w-0">
      <div
        className={cn(
          'autocomplete-combobox__control flex min-h-10 items-center overflow-hidden rounded-xl border bg-card/80 shadow-sm transition-all duration-200',
          hasError
            ? 'border-destructive/80 ring-1 ring-destructive/30'
            : 'border-border/90 focus-within:border-primary/40',
          !disabled && 'focus-within:shadow-md',
        )}
      >
        <input
          ref={inputRef}
          type="text"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={showMenu}
          aria-controls={listboxId}
          aria-activedescendant={activeSuggestion != null ? `${listboxId}-${activeIndex}` : undefined}
          value={value}
          placeholder={placeholder}
          disabled={disabled}
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          className="autocomplete-combobox__input min-w-0 flex-1 border-0 bg-transparent px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-0 disabled:cursor-not-allowed"
          onFocus={() => {
            if (filteredSuggestions.length > 0 || emptyState != null) {
              setIsOpen(true);
            }
          }}
          onChange={(event) => {
            onValueChange(event.target.value);
            setIsOpen(true);
            setActiveIndex(0);
          }}
          onKeyDown={handleKeyDown}
        />
        <ComboboxTrigger disabled={disabled} showMenu={showMenu} onToggleMenu={handleToggleMenu} />
      </div>

      {showMenu && (
        <ComboboxMenu
          listboxId={listboxId}
          activeIndex={activeIndex}
          selectedSuggestion={selectedSuggestion}
          filteredSuggestions={filteredSuggestions}
          emptyState={emptyState}
          onSelectSuggestion={handleSelectSuggestion}
        />
      )}
    </div>
  );
}

function ComboboxTrigger({ disabled, showMenu, onToggleMenu }: ComboboxTriggerProps): ReactElement {
  return (
    <button
      type="button"
      className={cn(
        'autocomplete-combobox__trigger flex h-full shrink-0 items-center px-3 text-muted-foreground transition-colors',
        disabled ? 'cursor-not-allowed opacity-60' : 'hover:text-foreground',
      )}
      aria-label="Toggle suggestions"
      aria-haspopup="listbox"
      aria-expanded={showMenu}
      disabled={disabled}
      onMouseDown={(event) => {
        event.preventDefault();
        onToggleMenu();
      }}
    >
      <FiChevronDown className={cn('h-4 w-4 transition-transform duration-200', showMenu && 'rotate-180')} />
    </button>
  );
}

function ComboboxMenu({
  listboxId,
  activeIndex,
  selectedSuggestion,
  filteredSuggestions,
  emptyState,
  onSelectSuggestion,
}: ComboboxMenuProps): ReactElement {
  return (
    <div
      className="autocomplete-combobox__menu absolute inset-x-0 top-full z-20 mt-2 overflow-hidden rounded-2xl border border-border/80 bg-popover/95 shadow-lg ring-1 ring-black/5 backdrop-blur-sm"
      role="presentation"
    >
      <div id={listboxId} role="listbox" className="autocomplete-combobox__list max-h-64 overflow-y-auto p-2">
        {filteredSuggestions.length > 0 ? (
          filteredSuggestions.map((suggestion, index) => (
            <ComboboxOption
              key={suggestion}
              id={`${listboxId}-${index}`}
              suggestion={suggestion}
              isActive={index === activeIndex}
              isSelected={suggestion === selectedSuggestion}
              onSelectSuggestion={onSelectSuggestion}
            />
          ))
        ) : (
          <div className="autocomplete-combobox__empty px-3 py-2 text-sm text-muted-foreground">
            {emptyState}
          </div>
        )}
      </div>
    </div>
  );
}

interface ComboboxOptionProps {
  id: string;
  suggestion: string;
  isActive: boolean;
  isSelected: boolean;
  onSelectSuggestion: (value: string) => void;
}

function ComboboxOption({
  id,
  suggestion,
  isActive,
  isSelected,
  onSelectSuggestion,
}: ComboboxOptionProps): ReactElement {
  return (
    <button
      id={id}
      type="button"
      role="option"
      aria-selected={isActive}
      className={cn(
        'autocomplete-combobox__option flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left text-sm transition-colors',
        isActive ? 'bg-primary/10 text-foreground' : 'text-foreground/80 hover:bg-accent/70 hover:text-foreground',
      )}
      onMouseDown={(event) => {
        event.preventDefault();
        onSelectSuggestion(suggestion);
      }}
    >
      <span className="truncate">{suggestion}</span>
      <FiCheck className={cn('ml-auto h-4 w-4 shrink-0 text-primary transition-opacity', isSelected ? 'opacity-100' : 'opacity-0')} />
    </button>
  );
}
