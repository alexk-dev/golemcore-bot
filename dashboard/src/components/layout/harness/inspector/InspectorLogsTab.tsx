import { useMemo, useState } from 'react';
import { FiAlertCircle } from 'react-icons/fi';
import { useInspectorLogs } from '../../../../hooks/useInspectorLogs';
import { useChatSessionStore } from '../../../../store/chatSessionStore';
import { useChatRuntimeStore } from '../../../../store/chatRuntimeStore';
import type { LogEntryResponse } from '../../../../api/system';

const LEVELS = ['ALL', 'INFO', 'WARN', 'ERROR', 'DEBUG'] as const;

type LevelFilter = typeof LEVELS[number];

function formatLogTime(timestamp: string): string {
  const d = new Date(timestamp);
  if (Number.isNaN(d.getTime())) {
    return timestamp;
  }
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
}

function levelClassName(level: string): string {
  const upper = level.toUpperCase();
  if (upper === 'ERROR') {
    return 'inspector-log__level inspector-log__level--error';
  }
  if (upper === 'WARN' || upper === 'WARNING') {
    return 'inspector-log__level inspector-log__level--warn';
  }
  return 'inspector-log__level';
}

interface SessionFilters {
  conversationKey: string;
  shortKey: string;
  sessionRecordId: string | null;
}

function buildSessionFilters(conversationKey: string, sessionRecordId: string | null): SessionFilters {
  return {
    conversationKey: conversationKey.toLowerCase(),
    shortKey: conversationKey.slice(0, 8).toLowerCase(),
    sessionRecordId: sessionRecordId != null ? sessionRecordId.toLowerCase() : null,
  };
}

function entryMatchesSession(entry: LogEntryResponse, session: SessionFilters): boolean {
  const haystack = `${entry.message ?? ''} ${entry.logger ?? ''} ${entry.thread ?? ''}`.toLowerCase();
  if (session.conversationKey.length > 0 && haystack.includes(session.conversationKey)) {
    return true;
  }
  if (session.shortKey.length > 0 && haystack.includes(session.shortKey)) {
    return true;
  }
  if (session.sessionRecordId != null && haystack.includes(session.sessionRecordId)) {
    return true;
  }
  return false;
}

interface FilterArgs {
  level: LevelFilter;
  query: string;
  scopeToSession: boolean;
  session: SessionFilters;
}

function filterEntries(entries: LogEntryResponse[], args: FilterArgs): LogEntryResponse[] {
  const trimmedQuery = args.query.trim().toLowerCase();
  return entries.filter((entry) => {
    if (args.level !== 'ALL' && entry.level.toUpperCase() !== args.level) {
      return false;
    }
    if (args.scopeToSession && !entryMatchesSession(entry, args.session)) {
      return false;
    }
    if (trimmedQuery.length === 0) {
      return true;
    }
    const haystack = `${entry.message ?? ''} ${entry.logger ?? ''}`.toLowerCase();
    return haystack.includes(trimmedQuery);
  });
}

export default function InspectorLogsTab() {
  const { data, isLoading, isError, refetch } = useInspectorLogs();
  const conversationKey = useChatSessionStore((s) => s.activeSessionId);
  const sessionRecordId = useChatRuntimeStore((s) => s.sessions[conversationKey]?.sessionRecordId ?? null);
  const [level, setLevel] = useState<LevelFilter>('ALL');
  const [query, setQuery] = useState('');
  const [scopeToSession, setScopeToSession] = useState(true);

  const sessionFilters = useMemo(
    () => buildSessionFilters(conversationKey, sessionRecordId),
    [conversationKey, sessionRecordId],
  );
  const filtered = useMemo(
    () => filterEntries(data?.entries ?? [], { level, query, scopeToSession, session: sessionFilters }),
    [data, level, query, scopeToSession, sessionFilters],
  );

  if (isLoading) {
    return <div className="harness-inspector__placeholder"><span>Loading logs…</span></div>;
  }
  if (isError) {
    return (
      <div className="harness-inspector__placeholder">
        <FiAlertCircle aria-hidden="true" />
        <span>Failed to load logs</span>
        <button type="button" className="agent-btn" onClick={() => refetch()}>Retry</button>
      </div>
    );
  }

  return (
    <div className="harness-inspector__placeholder-stack">
      <div className="inspector-log__filters">
        <label className="inspector-log__filter">
          <span>Level</span>
          <select value={level} onChange={(event) => setLevel(event.target.value as LevelFilter)}>
            {LEVELS.map((value) => <option key={value} value={value}>{value}</option>)}
          </select>
        </label>
        <input
          className="inspector-log__search"
          placeholder="Search logs…"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          aria-label="Search logs"
        />
      </div>
      <label className="inspector-log__scope">
        <input
          type="checkbox"
          checked={scopeToSession}
          onChange={(event) => setScopeToSession(event.target.checked)}
        />
        <span>Only this session ({conversationKey.slice(0, 8) || '—'})</span>
      </label>
      {filtered.length === 0 ? (
        <p className="harness-inspector__card-label">
          {scopeToSession
            ? 'No log entries reference this chat session yet. Disable the session scope to inspect runtime-wide logs.'
            : 'No logs match the current filter.'}
        </p>
      ) : (
        <ul className="inspector-log__list" role="list">
          {filtered.map((entry) => (
            <li key={entry.seq} className="inspector-log__row">
              <span className="inspector-log__time">{formatLogTime(entry.timestamp)}</span>
              <span className={levelClassName(entry.level)}>{entry.level}</span>
              <span className="inspector-log__message">{entry.message ?? ''}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
