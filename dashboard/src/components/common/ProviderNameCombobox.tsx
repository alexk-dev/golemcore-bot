import { type KeyboardEvent, type ReactElement, useEffect, useId, useMemo, useRef, useState } from 'react';
import { Input } from '../ui/field';
import { cn } from '../../lib/utils';

export interface ProviderNameComboboxProps {
  value: string;
  suggestions: string[];
  placeholder?: string;
  disabled?: boolean;
  hasError?: boolean;
  onValueChange: (value: string) => void;
  onSubmit?: () => void;
}

function normalizeSuggestions(suggestions: string[], value: string): string[] {
  const loweredValue = value.trim().toLowerCase();
  const uniqueSuggestions = Array.from(new Set(suggestions));
  return uniqueSuggestions
    .filter((suggestion) => suggestion.toLowerCase().includes(loweredValue))
    .sort((left, right) => left.localeCompare(right));
}

export function ProviderNameCombobox({
  value,
  suggestions,
  placeholder,
  disabled = false,
  hasError = false,
  onValueChange,
  onSubmit,
}: ProviderNameComboboxProps): ReactElement {
  const listboxId = useId();
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [isOpen, setIsOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState<number>(-1);

  const filteredSuggestions = useMemo(() => normalizeSuggestions(suggestions, value), [suggestions, value]);
  const activeSuggestion = activeIndex >= 0 ? filteredSuggestions[activeIndex] : null;

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

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>): void {
    if (!isOpen && (event.key === 'ArrowDown' || event.key === 'ArrowUp') && filteredSuggestions.length > 0) {
      event.preventDefault();
      setIsOpen(true);
      setActiveIndex(0);
      return;
    }

    if (!isOpen) {
      if (event.key === 'Enter' && onSubmit != null) {
        event.preventDefault();
        onSubmit();
      }
      return;
    }

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActiveIndex((currentIndex) => {
        if (filteredSuggestions.length === 0) {
          return -1;
        }
        if (currentIndex < 0) {
          return 0;
        }
        return (currentIndex + 1) % filteredSuggestions.length;
      });
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActiveIndex((currentIndex) => {
        if (filteredSuggestions.length === 0) {
          return -1;
        }
        if (currentIndex < 0) {
          return filteredSuggestions.length - 1;
        }
        return (currentIndex - 1 + filteredSuggestions.length) % filteredSuggestions.length;
      });
      return;
    }

    if (event.key === 'Enter') {
      event.preventDefault();
      if (activeSuggestion != null) {
        handleSelectSuggestion(activeSuggestion);
      } else if (onSubmit != null) {
        onSubmit();
      }
      return;
    }

    if (event.key === 'Escape') {
      event.preventDefault();
      setIsOpen(false);
      setActiveIndex(-1);
    }
  }

  return (
    <div ref={wrapperRef} className="provider-name-combobox position-relative">
      <Input
        ref={inputRef}
        role="combobox"
        aria-autocomplete="list"
        aria-expanded={isOpen}
        aria-controls={listboxId}
        aria-activedescendant={activeSuggestion != null ? `${listboxId}-${activeIndex}` : undefined}
        value={value}
        placeholder={placeholder}
        disabled={disabled}
        autoCapitalize="off"
        autoCorrect="off"
        spellCheck={false}
        className={cn(hasError && 'border-destructive')}
        onFocus={() => {
          if (filteredSuggestions.length > 0) {
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
      {isOpen && filteredSuggestions.length > 0 && (
        <div className="provider-name-combobox__menu shadow-sm" role="presentation">
          <div id={listboxId} role="listbox" className="provider-name-combobox__list">
            {filteredSuggestions.map((suggestion, index) => {
              const isActive = index === activeIndex;
              return (
                <button
                  key={suggestion}
                  id={`${listboxId}-${index}`}
                  type="button"
                  role="option"
                  aria-selected={isActive}
                  className={cn(
                    'provider-name-combobox__option',
                    isActive && 'provider-name-combobox__option--active',
                  )}
                  onMouseDown={(event) => {
                    event.preventDefault();
                    handleSelectSuggestion(suggestion);
                  }}
                >
                  {suggestion}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
