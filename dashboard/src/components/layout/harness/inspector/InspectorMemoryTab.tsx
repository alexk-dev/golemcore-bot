import { useEffect, useMemo, useState } from 'react';
import { FiFilter, FiSearch } from 'react-icons/fi';
import { useChatRuntimeStore } from '../../../../store/chatRuntimeStore';
import { useChatSessionStore } from '../../../../store/chatSessionStore';
import { useRelevantMemories } from '../../../../hooks/useRelevantMemories';
import MemoryArtifactCard from './MemoryArtifactCard';
import {
  buildTypeFilterOptions,
  filterMemoryItems,
  type MemoryTypeFilter,
} from './memoryFormat';

const TYPE_FILTERS = buildTypeFilterOptions();

function deriveQueryFromMessages(messages: ReturnType<typeof useChatRuntimeStore.getState>['sessions'][string]['messages'] | undefined): string {
  if (messages == null) {
    return '';
  }
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (message.role === 'user' && message.content.length > 0) {
      return message.content.slice(0, 256);
    }
  }
  return '';
}

export default function InspectorMemoryTab() {
  const sessionId = useChatSessionStore((s) => s.activeSessionId);
  const session = useChatRuntimeStore((s) => s.sessions[sessionId]);
  const queryHint = useMemo(() => deriveQueryFromMessages(session?.messages), [session?.messages]);
  const [typeFilter, setTypeFilter] = useState<MemoryTypeFilter>('all');
  const [search, setSearch] = useState('');
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [now, setNow] = useState(() => Date.now());

  const { data, isLoading, isError, refetch } = useRelevantMemories({
    sessionId,
    query: queryHint,
    autoRefresh,
  });

  // Tick once a minute to keep the relative timestamps ("Just now", "2 days ago") fresh.
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(id);
  }, []);

  const items = useMemo(() => data?.items ?? [], [data?.items]);
  const filtered = useMemo(() => filterMemoryItems(items, typeFilter, search), [items, typeFilter, search]);

  if (isError) {
    return (
      <div className="harness-inspector__placeholder" role="alert">
        <span>Failed to load relevant memories</span>
        <button type="button" className="agent-btn" onClick={() => refetch()}>Retry</button>
      </div>
    );
  }

  return (
    <div className="harness-inspector__placeholder-stack">
      <header className="memory-tab__header">
        <div className="memory-tab__title-row">
          <h3 className="harness-inspector__card-title">Relevant memories</h3>
          <span className="memory-tab__count">{items.length}</span>
        </div>
        <label className="memory-tab__toggle">
          <span>Auto-refresh</span>
          <input
            type="checkbox"
            checked={autoRefresh}
            onChange={(event) => setAutoRefresh(event.target.checked)}
          />
        </label>
      </header>
      <div className="memory-tab__filters">
        <label className="memory-tab__select">
          <FiFilter size={14} aria-hidden="true" />
          <select
            value={typeFilter}
            onChange={(event) => setTypeFilter(event.target.value as MemoryTypeFilter)}
            aria-label="Filter memories by type"
          >
            {TYPE_FILTERS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
        <label className="memory-tab__search">
          <FiSearch size={14} aria-hidden="true" />
          <input
            type="text"
            placeholder="Search memories…"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            aria-label="Search memories"
          />
        </label>
      </div>
      {isLoading ? (
        <p className="harness-inspector__card-label">Loading memories…</p>
      ) : filtered.length === 0 ? (
        <p className="harness-inspector__card-label">
          {items.length === 0
            ? 'The runtime has not surfaced any memories for this session yet. They will appear here once Memory V2 records relate to the active conversation.'
            : 'No memories match the current filter.'}
        </p>
      ) : (
        <div className="memory-tab__list">
          {filtered.map((item) => (
            <MemoryArtifactCard key={item.id} item={item} now={now} />
          ))}
        </div>
      )}
    </div>
  );
}
