import { type Dispatch, type MutableRefObject, type SetStateAction, useCallback, useEffect, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import { getSystemLogs, type LogEntryResponse } from '../../api/system';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import {
  INITIAL_PAGE_SIZE,
  MAX_BUFFERED_WHILE_PAUSED,
  MAX_IN_MEMORY,
  OLDER_PAGE_SIZE,
  RECONNECT_DELAY_MS,
} from './logConstants';
import { appendLiveEntries, normalizeBatch, prependOlderEntries } from './logUtils';
import type { LogBatchPayload } from './logTypes';

interface UseLogsDataResult {
  entries: LogEntryResponse[];
  hasMoreOlder: boolean;
  isLoadingInitial: boolean;
  isLoadingOlder: boolean;
  connected: boolean;
  isPaused: boolean;
  bufferedCount: number;
  droppedCount: number;
  eventsPerSecond: number;
  lastEventAt: string | null;
  loadError: string | null;
  loadLatest: () => Promise<void>;
  loadOlder: (beforeSeq: number) => Promise<void>;
  togglePaused: () => void;
  clearView: () => void;
}

function clearReconnectTimer(ref: MutableRefObject<ReturnType<typeof setTimeout> | null>): void {
  if (ref.current == null) {
    return;
  }
  clearTimeout(ref.current);
  ref.current = null;
}

function buildLogsSocketUrl(token: string, afterSeq: number): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws/logs?token=${encodeURIComponent(token)}&afterSeq=${afterSeq}`;
}

interface RealtimeBatchContext {
  isPausedRef: MutableRefObject<boolean>;
  pausedBufferRef: MutableRefObject<LogEntryResponse[]>;
  perSecondCounterRef: MutableRefObject<number>;
  setLastEventAt: Dispatch<SetStateAction<string | null>>;
  setBufferedCount: Dispatch<SetStateAction<number>>;
  setDroppedCount: Dispatch<SetStateAction<number>>;
  setEntries: Dispatch<SetStateAction<LogEntryResponse[]>>;
}

function applyRealtimeBatch(batch: LogEntryResponse[], context: RealtimeBatchContext): void {
  context.perSecondCounterRef.current += batch.length;
  context.setLastEventAt(batch[batch.length - 1].timestamp);

  if (context.isPausedRef.current) {
    const nextBuffer = [...context.pausedBufferRef.current, ...batch];
    const capped = nextBuffer.slice(-MAX_BUFFERED_WHILE_PAUSED);
    const dropped = Math.max(0, nextBuffer.length - capped.length);
    context.pausedBufferRef.current = capped;
    context.setBufferedCount(capped.length);
    if (dropped > 0) {
      context.setDroppedCount((prev) => prev + dropped);
    }
    return;
  }

  context.setEntries((prev) => appendLiveEntries(prev, batch, MAX_IN_MEMORY));
}

export function useLogsData(token: string | null): UseLogsDataResult {
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const loadOlderInFlightRef = useRef<boolean>(false);
  const pausedBufferRef = useRef<LogEntryResponse[]>([]);
  const lastSeqRef = useRef<number>(0);
  const isPausedRef = useRef<boolean>(false);
  const perSecondCounterRef = useRef<number>(0);

  const [entries, setEntries] = useState<LogEntryResponse[]>([]);
  const [hasMoreOlder, setHasMoreOlder] = useState<boolean>(false);
  const [isLoadingInitial, setIsLoadingInitial] = useState<boolean>(true);
  const [isLoadingOlder, setIsLoadingOlder] = useState<boolean>(false);
  const [initialLoadDone, setInitialLoadDone] = useState<boolean>(false);
  const [connected, setConnected] = useState<boolean>(false);
  const [isPaused, setIsPaused] = useState<boolean>(false);
  const [bufferedCount, setBufferedCount] = useState<number>(0);
  const [droppedCount, setDroppedCount] = useState<number>(0);
  const [eventsPerSecond, setEventsPerSecond] = useState<number>(0);
  const [lastEventAt, setLastEventAt] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const flushBufferedEntries = useCallback((): void => {
    const pending = pausedBufferRef.current;
    if (pending.length === 0) {
      return;
    }
    pausedBufferRef.current = [];
    setBufferedCount(0);
    setEntries((prev) => appendLiveEntries(prev, pending, MAX_IN_MEMORY));
  }, []);

  const loadLatest = useCallback(async (): Promise<void> => {
    setIsLoadingInitial(true);
    setLoadError(null);
    try {
      const response = await getSystemLogs({ limit: INITIAL_PAGE_SIZE });
      setEntries(response.items);
      setHasMoreOlder(response.hasMore);
      pausedBufferRef.current = [];
      setBufferedCount(0);
      setDroppedCount(0);
      setLastEventAt(response.items.length > 0 ? response.items[response.items.length - 1].timestamp : null);
    } catch (error: unknown) {
      setLoadError(extractErrorMessage(error));
    } finally {
      setIsLoadingInitial(false);
      setInitialLoadDone(true);
    }
  }, []);

  const loadOlder = useCallback(async (beforeSeq: number): Promise<void> => {
    if (loadOlderInFlightRef.current || !hasMoreOlder) {
      return;
    }

    loadOlderInFlightRef.current = true;
    setIsLoadingOlder(true);
    try {
      const response = await getSystemLogs({ beforeSeq, limit: OLDER_PAGE_SIZE });
      setEntries((prev) => prependOlderEntries(prev, response.items, MAX_IN_MEMORY));
      setHasMoreOlder(response.hasMore);
    } catch (error: unknown) {
      toast.error(`Failed to load older logs: ${extractErrorMessage(error)}`);
    } finally {
      loadOlderInFlightRef.current = false;
      setIsLoadingOlder(false);
    }
  }, [hasMoreOlder]);

  const clearView = useCallback((): void => {
    setEntries([]);
    setHasMoreOlder(false);
    pausedBufferRef.current = [];
    setBufferedCount(0);
    setDroppedCount(0);
    setLastEventAt(null);
  }, []);

  const togglePaused = useCallback((): void => {
    setIsPaused((prev) => !prev);
  }, []);

  // Initial sync: load last page once on mount.
  useEffect(() => {
    void loadLatest();
  }, [loadLatest]);

  // Keep mutable ref in sync with pause toggle and flush backlog on resume.
  useEffect(() => {
    isPausedRef.current = isPaused;
    if (!isPaused) {
      flushBufferedEntries();
    }
  }, [flushBufferedEntries, isPaused]);

  // Track latest sequence so reconnect can request delta stream from correct cursor.
  useEffect(() => {
    if (entries.length === 0) {
      lastSeqRef.current = 0;
      return;
    }
    lastSeqRef.current = entries[entries.length - 1].seq;
  }, [entries]);

  // Derive a stable events-per-second counter for operator feedback.
  useEffect(() => {
    const timer = setInterval(() => {
      setEventsPerSecond(perSecondCounterRef.current);
      perSecondCounterRef.current = 0;
    }, 1000);
    return () => {
      clearInterval(timer);
    };
  }, []);

  // WebSocket lifecycle and resilient reconnect with sequence cursor recovery.
  useEffect(() => {
    clearReconnectTimer(reconnectTimerRef);

    if (token == null || token.length === 0 || !initialLoadDone) {
      setConnected(false);
      wsRef.current?.close();
      wsRef.current = null;
      return;
    }

    let disposed = false;

    const openSocket = (): void => {
      if (disposed) {
        return;
      }

      const afterSeq = Math.max(0, lastSeqRef.current);
      const ws = new WebSocket(buildLogsSocketUrl(token, afterSeq));
      wsRef.current = ws;

      ws.onopen = (): void => {
        if (disposed) {
          ws.close();
          return;
        }
        setConnected(true);
        clearReconnectTimer(reconnectTimerRef);
      };

      ws.onclose = (): void => {
        if (disposed) {
          return;
        }
        setConnected(false);
        clearReconnectTimer(reconnectTimerRef);
        reconnectTimerRef.current = setTimeout(() => {
          openSocket();
        }, RECONNECT_DELAY_MS);
      };

      ws.onmessage = (event: MessageEvent<unknown>): void => {
        if (disposed || typeof event.data !== 'string') {
          return;
        }

        try {
          const payload = JSON.parse(event.data) as LogBatchPayload;
          if (payload.type !== 'log_batch') {
            return;
          }
          const batch = normalizeBatch(payload);
          if (batch.length === 0) {
            return;
          }
          applyRealtimeBatch(batch, {
            isPausedRef,
            pausedBufferRef,
            perSecondCounterRef,
            setLastEventAt,
            setBufferedCount,
            setDroppedCount,
            setEntries,
          });
        } catch {
          // Keep stream resilient for malformed payloads.
        }
      };
    };

    openSocket();

    return () => {
      disposed = true;
      clearReconnectTimer(reconnectTimerRef);
      setConnected(false);
      const socket = wsRef.current;
      wsRef.current = null;
      socket?.close();
    };
  }, [initialLoadDone, token]);

  return {
    entries,
    hasMoreOlder,
    isLoadingInitial,
    isLoadingOlder,
    connected,
    isPaused,
    bufferedCount,
    droppedCount,
    eventsPerSecond,
    lastEventAt,
    loadError,
    loadLatest,
    loadOlder,
    togglePaused,
    clearView,
  };
}
