import type { KeyboardEventHandler, MutableRefObject, ReactElement } from 'react';
import { Card } from 'react-bootstrap';
import { formatCompactTimestamp, levelVariant } from './logUtils';
import type { LogsViewRow } from './logTypes';

export interface LogsListCardProps {
  listRef: MutableRefObject<HTMLDivElement | null>;
  visibleRows: LogsViewRow[];
  topSpacerHeight: number;
  bottomSpacerHeight: number;
  loadedCount: number;
  filteredCount: number;
  hasMoreOlder: boolean;
  isLoadingOlder: boolean;
  onScroll: () => void;
  onListKeyDown: KeyboardEventHandler<HTMLDivElement>;
  activeDescendantId: string | undefined;
  onSelectSeq: (seq: number) => void;
}

export function LogsListCard(props: LogsListCardProps): ReactElement {
  const {
    listRef,
    visibleRows,
    topSpacerHeight,
    bottomSpacerHeight,
    loadedCount,
    filteredCount,
    hasMoreOlder,
    isLoadingOlder,
    onScroll,
    onListKeyDown,
    activeDescendantId,
    onSelectSeq,
  } = props;

  return (
    <Card>
      <Card.Header className="d-flex align-items-center justify-content-between logs-header">
        <div className="small text-body-secondary">
          Showing {filteredCount.toLocaleString()} of {loadedCount.toLocaleString()} loaded rows
        </div>
        <div className="small text-body-secondary d-flex align-items-center gap-3">
          {isLoadingOlder && <span>Loading older...</span>}
          {hasMoreOlder ? <span>Scroll up to load more</span> : <span>Oldest available reached</span>}
        </div>
      </Card.Header>
      <div
        className="logs-viewport"
        ref={listRef}
        onScroll={onScroll}
        onKeyDown={onListKeyDown}
        role="listbox"
        tabIndex={0}
        aria-label="Log entries"
        aria-activedescendant={activeDescendantId}
      >
        {filteredCount === 0 ? (
          <div className="logs-empty text-body-secondary">No logs for current filters.</div>
        ) : (
          <>
            <div style={{ height: `${topSpacerHeight}px` }} />
            {visibleRows.map((row) => (
              <div
                key={row.entry.seq}
                id={`logs-option-${row.entry.seq}`}
                role="option"
                aria-selected={row.isSelected}
                className={`logs-row ${row.isSelected ? 'selected' : ''}`}
                onClick={() => onSelectSeq(row.entry.seq)}
              >
                <span className="logs-cell logs-cell-time">{formatCompactTimestamp(row.entry.timestamp)}</span>
                <span className={`logs-cell logs-cell-level text-${levelVariant(row.entry.level)}`}>{row.entry.level}</span>
                <span className="logs-cell logs-cell-logger" title={row.entry.logger ?? ''}>{row.entry.logger ?? 'n/a'}</span>
                <span className="logs-cell logs-cell-message" title={row.entry.message ?? ''}>{row.entry.message ?? ''}</span>
              </div>
            ))}
            <div style={{ height: `${bottomSpacerHeight}px` }} />
          </>
        )}
      </div>
    </Card>
  );
}
