import { useMemo, useState } from 'react';
import { FiAlertCircle } from 'react-icons/fi';
import { useInspectorLogs } from '../../../../hooks/useInspectorLogs';
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

function filterEntries(entries: LogEntryResponse[], level: LevelFilter, query: string): LogEntryResponse[] {
  return entries.filter((entry) => {
    if (level !== 'ALL' && entry.level.toUpperCase() !== level) {
      return false;
    }
    if (query.trim().length === 0) {
      return true;
    }
    const haystack = `${entry.message ?? ''} ${entry.logger ?? ''}`.toLowerCase();
    return haystack.includes(query.trim().toLowerCase());
  });
}

export default function InspectorLogsTab() {
  const { data, isLoading, isError, refetch } = useInspectorLogs();
  const [level, setLevel] = useState<LevelFilter>('ALL');
  const [query, setQuery] = useState('');
  const filtered = useMemo(() => filterEntries(data?.entries ?? [], level, query), [data, level, query]);

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
      {filtered.length === 0 ? (
        <p className="harness-inspector__card-label">No logs match the current filter.</p>
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
