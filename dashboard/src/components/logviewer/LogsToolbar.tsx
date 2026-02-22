import type { ReactElement } from 'react';
import { Badge, Button, ButtonGroup } from 'react-bootstrap';

export interface LogsToolbarProps {
  connected: boolean;
  isPaused: boolean;
  autoScroll: boolean;
  bufferedCount: number;
  droppedCount: number;
  eventsPerSecond: number;
  lastEventAt: string | null;
  onTogglePause: () => void;
  onJumpToLatest: () => void;
  onReload: () => void;
  onClearView: () => void;
}

export function LogsToolbar(props: LogsToolbarProps): ReactElement {
  const {
    connected,
    isPaused,
    autoScroll,
    bufferedCount,
    droppedCount,
    eventsPerSecond,
    lastEventAt,
    onTogglePause,
    onJumpToLatest,
    onReload,
    onClearView,
  } = props;

  const formattedLastEvent = (() => {
    if (lastEventAt == null) {
      return 'n/a';
    }
    const parsed = new Date(lastEventAt);
    if (Number.isNaN(parsed.getTime())) {
      return 'n/a';
    }
    return parsed.toLocaleTimeString();
  })();

  return (
    <div className="section-header logs-toolbar">
      <div className="d-flex align-items-center justify-content-between flex-wrap gap-2">
        <h4 className="mb-0">Logs</h4>
        <Badge bg={connected ? 'success' : 'secondary'}>{connected ? 'Connected' : 'Reconnecting'}</Badge>
      </div>

      <div className="logs-toolbar-actions mt-2">
        <ButtonGroup size="sm" aria-label="Primary logs actions">
          <Button variant={isPaused ? 'warning' : 'secondary'} onClick={onTogglePause}>
            {isPaused ? 'Resume' : 'Pause'}
          </Button>
          <Button variant={autoScroll ? 'primary' : 'secondary'} onClick={onJumpToLatest}>
            Jump To Latest
          </Button>
        </ButtonGroup>
        <ButtonGroup size="sm" aria-label="Secondary logs actions">
          <Button variant="secondary" onClick={onReload}>
            Reload
          </Button>
          <Button
            variant="secondary"
            onClick={onClearView}
            title="Clears only the local dashboard buffer. Server logs are not deleted."
          >
            Clear Local Buffer
          </Button>
        </ButtonGroup>
      </div>

      <div className="logs-toolbar-meta mt-2 small text-body-secondary">
        <span>rate: {eventsPerSecond}/s</span>
        <span>last event: {formattedLastEvent}</span>
        <span>buffered: {bufferedCount}</span>
        <span>dropped: {droppedCount}</span>
      </div>
    </div>
  );
}
