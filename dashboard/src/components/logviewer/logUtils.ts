import type { LogEntryResponse } from '../../api/system';
import type { LogBatchPayload } from './logTypes';

const FALLBACK_MIN_SEQ = Number.MIN_SAFE_INTEGER;
const FALLBACK_MAX_SEQ = Number.MAX_SAFE_INTEGER;

export function formatCompactTimestamp(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }
  return `${parsed.toLocaleDateString()} ${parsed.toLocaleTimeString()}`;
}

export function normalizeBatch(payload: LogBatchPayload): LogEntryResponse[] {
  if (!Array.isArray(payload.items)) {
    return [];
  }

  const normalized: LogEntryResponse[] = [];
  for (const item of payload.items) {
    if (typeof item !== 'object' || item == null) {
      continue;
    }
    const candidate = item as Partial<LogEntryResponse>;
    if (typeof candidate.seq !== 'number' || typeof candidate.timestamp !== 'string' || typeof candidate.level !== 'string') {
      continue;
    }
    normalized.push({
      seq: candidate.seq,
      timestamp: candidate.timestamp,
      level: candidate.level,
      logger: typeof candidate.logger === 'string' ? candidate.logger : null,
      thread: typeof candidate.thread === 'string' ? candidate.thread : null,
      message: typeof candidate.message === 'string' ? candidate.message : null,
      exception: typeof candidate.exception === 'string' ? candidate.exception : null,
    });
  }

  return normalized.sort((a, b) => a.seq - b.seq);
}

export function appendLiveEntries(
  prev: LogEntryResponse[],
  incoming: LogEntryResponse[],
  maxInMemory: number
): LogEntryResponse[] {
  if (incoming.length === 0) {
    return prev;
  }

  const lastSeq = prev.length > 0 ? prev[prev.length - 1].seq : FALLBACK_MIN_SEQ;
  const unique = incoming.filter((entry) => entry.seq > lastSeq);
  if (unique.length === 0) {
    return prev;
  }

  const next = [...prev, ...unique];
  if (next.length <= maxInMemory) {
    return next;
  }
  return next.slice(next.length - maxInMemory);
}

export function prependOlderEntries(
  prev: LogEntryResponse[],
  incoming: LogEntryResponse[],
  maxInMemory: number
): LogEntryResponse[] {
  if (incoming.length === 0) {
    return prev;
  }

  const firstSeq = prev.length > 0 ? prev[0].seq : FALLBACK_MAX_SEQ;
  const unique = incoming.filter((entry) => entry.seq < firstSeq);
  if (unique.length === 0) {
    return prev;
  }

  const next = [...unique, ...prev];
  if (next.length <= maxInMemory) {
    return next;
  }
  return next.slice(0, maxInMemory);
}

export function levelVariant(level: string): string {
  switch (level) {
    case 'ERROR':
      return 'danger';
    case 'WARN':
      return 'warning';
    case 'INFO':
      return 'info';
    case 'DEBUG':
      return 'secondary';
    case 'TRACE':
      return 'dark';
    default:
      return 'secondary';
  }
}
