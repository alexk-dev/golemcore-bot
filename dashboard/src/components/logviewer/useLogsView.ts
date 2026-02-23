import { type KeyboardEvent, type MutableRefObject, useCallback, useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import type { LogEntryResponse } from '../../api/system';
import {
  AUTO_SCROLL_THRESHOLD_PX,
  DEFAULT_LEVELS,
  LOAD_MORE_THRESHOLD_PX,
  ROW_HEIGHT_PX,
  ROW_OVERSCAN,
} from './logConstants';
import type { LogLevel, LogLevelFilter, LogsViewRow } from './logTypes';
import { useDebouncedValue } from './useDebouncedValue';

interface UseLogsViewParams {
  entries: LogEntryResponse[];
  hasMoreOlder: boolean;
  isLoadingOlder: boolean;
  loadOlder: (beforeSeq: number) => Promise<void>;
}

interface UseLogsViewResult {
  listRef: MutableRefObject<HTMLDivElement | null>;
  autoScroll: boolean;
  selectedSeq: number | null;
  selectedEntry: LogEntryResponse | null;
  searchText: string;
  loggerFilter: string;
  enabledLevels: LogLevelFilter;
  visibleRows: LogsViewRow[];
  topSpacerHeight: number;
  bottomSpacerHeight: number;
  filteredCount: number;
  activeDescendantId: string | undefined;
  setSearchText: (value: string) => void;
  setLoggerFilter: (value: string) => void;
  setSelectedSeq: (value: number | null) => void;
  toggleLevel: (level: LogLevel) => void;
  handleScroll: () => void;
  handleListKeyDown: (event: KeyboardEvent<HTMLDivElement>) => void;
  jumpToLatest: () => void;
}

const FILTER_DEBOUNCE_MS = 180;

function getOptionId(seq: number): string {
  return `logs-option-${seq}`;
}

export function useLogsView(params: UseLogsViewParams): UseLogsViewResult {
  const { entries, hasMoreOlder, isLoadingOlder, loadOlder } = params;
  const listRef = useRef<HTMLDivElement | null>(null);
  const previousLastSeqRef = useRef<number | null>(null);

  const [searchText, setSearchText] = useState<string>('');
  const [loggerFilter, setLoggerFilter] = useState<string>('');
  const [enabledLevels, setEnabledLevels] = useState<LogLevelFilter>(DEFAULT_LEVELS);
  const [selectedSeq, setSelectedSeq] = useState<number | null>(null);
  const [autoScroll, setAutoScroll] = useState<boolean>(true);
  const [scrollTop, setScrollTop] = useState<number>(0);
  const [viewportHeight, setViewportHeight] = useState<number>(500);

  const debouncedSearch = useDebouncedValue(searchText, FILTER_DEBOUNCE_MS);
  const debouncedLogger = useDebouncedValue(loggerFilter, FILTER_DEBOUNCE_MS);
  const deferredSearch = useDeferredValue(debouncedSearch);
  const deferredLogger = useDeferredValue(debouncedLogger);

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'auto'): void => {
    const viewport = listRef.current;
    if (viewport == null) {
      return;
    }
    viewport.scrollTo({ top: viewport.scrollHeight, behavior });
    setScrollTop(viewport.scrollTop);
  }, []);

  const filteredEntries = useMemo(() => {
    const search = deferredSearch.trim().toLowerCase();
    const logger = deferredLogger.trim().toLowerCase();

    return entries.filter((entry) => {
      const level = entry.level.toUpperCase() as LogLevel;
      if (!(enabledLevels[level] ?? true)) {
        return false;
      }
      if (logger.length > 0 && !(entry.logger ?? '').toLowerCase().includes(logger)) {
        return false;
      }
      if (search.length === 0) {
        return true;
      }

      const haystack = `${entry.message ?? ''}\n${entry.exception ?? ''}\n${entry.logger ?? ''}`.toLowerCase();
      return haystack.includes(search);
    });
  }, [deferredLogger, deferredSearch, enabledLevels, entries]);

  const totalRows = filteredEntries.length;
  const startIndex = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT_PX) - ROW_OVERSCAN);
  const endIndex = Math.min(totalRows, Math.ceil((scrollTop + viewportHeight) / ROW_HEIGHT_PX) + ROW_OVERSCAN);

  const visibleRows = useMemo<LogsViewRow[]>(
    () => filteredEntries.slice(startIndex, endIndex).map((entry) => ({ entry, isSelected: selectedSeq === entry.seq })),
    [endIndex, filteredEntries, selectedSeq, startIndex]
  );
  const topSpacerHeight = startIndex * ROW_HEIGHT_PX;
  const bottomSpacerHeight = Math.max(0, (totalRows - endIndex) * ROW_HEIGHT_PX);

  const selectedEntry = useMemo(
    () => entries.find((entry) => entry.seq === selectedSeq) ?? null,
    [entries, selectedSeq]
  );

  const activeDescendantId = useMemo(() => {
    if (selectedSeq == null) {
      return undefined;
    }
    const isVisible = visibleRows.some((row) => row.entry.seq === selectedSeq);
    return isVisible ? getOptionId(selectedSeq) : undefined;
  }, [selectedSeq, visibleRows]);

  const selectByIndex = useCallback((targetIndex: number): void => {
    if (filteredEntries.length === 0) {
      return;
    }

    const clamped = Math.max(0, Math.min(filteredEntries.length - 1, targetIndex));
    const target = filteredEntries[clamped];
    setSelectedSeq(target.seq);

    const viewport = listRef.current;
    if (viewport == null) {
      return;
    }
    viewport.scrollTo({ top: clamped * ROW_HEIGHT_PX, behavior: 'auto' });
    setScrollTop(viewport.scrollTop);
  }, [filteredEntries]);

  const loadOlderWithCompensation = useCallback(async (): Promise<void> => {
    const viewport = listRef.current;
    const beforeSeq = entries[0]?.seq;
    if (viewport == null || beforeSeq == null) {
      return;
    }

    const previousScrollHeight = viewport.scrollHeight;
    const previousScrollTop = viewport.scrollTop;
    await loadOlder(beforeSeq);

    requestAnimationFrame(() => {
      const element = listRef.current;
      if (element == null) {
        return;
      }
      const nextScrollHeight = element.scrollHeight;
      element.scrollTop = nextScrollHeight - previousScrollHeight + previousScrollTop;
      setScrollTop(element.scrollTop);
    });
  }, [entries, loadOlder]);

  const handleScroll = useCallback((): void => {
    const viewport = listRef.current;
    if (viewport == null) {
      return;
    }

    setScrollTop(viewport.scrollTop);
    setViewportHeight(viewport.clientHeight);

    if (viewport.scrollTop <= LOAD_MORE_THRESHOLD_PX && hasMoreOlder && !isLoadingOlder && entries.length > 0) {
      void loadOlderWithCompensation();
    }

    const distanceToBottom = viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight;
    setAutoScroll(distanceToBottom <= AUTO_SCROLL_THRESHOLD_PX);
  }, [entries.length, hasMoreOlder, isLoadingOlder, loadOlderWithCompensation]);

  const handleListKeyDown = useCallback((event: KeyboardEvent<HTMLDivElement>): void => {
    if (filteredEntries.length === 0) {
      return;
    }

    const selectedIndex = selectedSeq != null
      ? filteredEntries.findIndex((entry) => entry.seq === selectedSeq)
      : -1;
    const pageStep = Math.max(1, Math.floor(viewportHeight / ROW_HEIGHT_PX) - 1);

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        selectByIndex(selectedIndex + 1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        selectByIndex(selectedIndex >= 0 ? selectedIndex - 1 : filteredEntries.length - 1);
        break;
      case 'PageDown':
        event.preventDefault();
        selectByIndex((selectedIndex >= 0 ? selectedIndex : 0) + pageStep);
        break;
      case 'PageUp':
        event.preventDefault();
        selectByIndex((selectedIndex >= 0 ? selectedIndex : filteredEntries.length - 1) - pageStep);
        break;
      case 'Home':
        event.preventDefault();
        selectByIndex(0);
        break;
      case 'End':
        event.preventDefault();
        selectByIndex(filteredEntries.length - 1);
        break;
      default:
        break;
    }
  }, [filteredEntries, selectedSeq, selectByIndex, viewportHeight]);

  const jumpToLatest = useCallback((): void => {
    setAutoScroll(true);
    requestAnimationFrame(() => scrollToBottom('smooth'));
  }, [scrollToBottom]);

  const toggleLevel = useCallback((level: LogLevel): void => {
    setEnabledLevels((prev) => ({ ...prev, [level]: !prev[level] }));
  }, []);

  // Keep viewport dimensions current for virtualization and keyboard page step.
  useEffect(() => {
    const viewport = listRef.current;
    if (viewport == null) {
      return;
    }
    setViewportHeight(viewport.clientHeight);
  }, [entries.length]);

  // Preserve valid selection when filters/data change, otherwise pick the latest row.
  useEffect(() => {
    const selectedExists = selectedSeq != null && entries.some((entry) => entry.seq === selectedSeq);
    if (selectedExists) {
      return;
    }
    setSelectedSeq(entries.length > 0 ? entries[entries.length - 1].seq : null);
  }, [entries, selectedSeq]);

  // Auto-stick to bottom only when the user has not scrolled away.
  useEffect(() => {
    const currentLastSeq = entries.length > 0 ? entries[entries.length - 1].seq : null;
    const previousLastSeq = previousLastSeqRef.current;
    previousLastSeqRef.current = currentLastSeq;

    if (currentLastSeq == null || !autoScroll) {
      return;
    }
    if (previousLastSeq != null && currentLastSeq <= previousLastSeq) {
      return;
    }
    requestAnimationFrame(() => scrollToBottom('auto'));
  }, [autoScroll, entries, scrollToBottom]);

  return {
    listRef,
    autoScroll,
    selectedSeq,
    selectedEntry,
    searchText,
    loggerFilter,
    enabledLevels,
    visibleRows,
    topSpacerHeight,
    bottomSpacerHeight,
    filteredCount: filteredEntries.length,
    activeDescendantId,
    setSearchText,
    setLoggerFilter,
    setSelectedSeq,
    toggleLevel,
    handleScroll,
    handleListKeyDown,
    jumpToLatest,
  };
}
