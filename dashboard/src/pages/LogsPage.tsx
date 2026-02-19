import type { ReactElement } from 'react';
import { Alert, Col, Row, Spinner } from 'react-bootstrap';
import { LogsDetailsCard } from '../components/logviewer/LogsDetailsCard';
import { LogsFilters } from '../components/logviewer/LogsFilters';
import { LogsListCard } from '../components/logviewer/LogsListCard';
import { LogsToolbar } from '../components/logviewer/LogsToolbar';
import { useLogsData } from '../components/logviewer/useLogsData';
import { useLogsView } from '../components/logviewer/useLogsView';
import { useAuthStore } from '../store/authStore';

export default function LogsPage(): ReactElement {
  const token = useAuthStore((state) => state.accessToken);
  const logsData = useLogsData(token);
  const logsView = useLogsView({
    entries: logsData.entries,
    hasMoreOlder: logsData.hasMoreOlder,
    isLoadingOlder: logsData.isLoadingOlder,
    loadOlder: logsData.loadOlder,
  });

  const handleReload = (): void => {
    void logsData.loadLatest();
  };

  const handleClearView = (): void => {
    logsData.clearView();
    logsView.setSelectedSeq(null);
  };

  if (logsData.isLoadingInitial) {
    return <Spinner />;
  }

  return (
    <div>
      <LogsToolbar
        connected={logsData.connected}
        isPaused={logsData.isPaused}
        autoScroll={logsView.autoScroll}
        bufferedCount={logsData.bufferedCount}
        droppedCount={logsData.droppedCount}
        eventsPerSecond={logsData.eventsPerSecond}
        lastEventAt={logsData.lastEventAt}
        onTogglePause={logsData.togglePaused}
        onJumpToLatest={logsView.jumpToLatest}
        onReload={handleReload}
        onClearView={handleClearView}
      />

      {logsData.loadError != null && (
        <Alert variant="danger" className="mb-3">
          Failed to load logs: {logsData.loadError}
        </Alert>
      )}

      <Alert variant="info" className="mb-3">
        Live stream with virtualized rendering. Older records are loaded on scroll to top.
      </Alert>

      <LogsFilters
        searchText={logsView.searchText}
        loggerFilter={logsView.loggerFilter}
        enabledLevels={logsView.enabledLevels}
        onSearchChange={logsView.setSearchText}
        onLoggerChange={logsView.setLoggerFilter}
        onToggleLevel={logsView.toggleLevel}
      />

      <Row className="g-3">
        <Col xl={8}>
          <LogsListCard
            listRef={logsView.listRef}
            visibleRows={logsView.visibleRows}
            topSpacerHeight={logsView.topSpacerHeight}
            bottomSpacerHeight={logsView.bottomSpacerHeight}
            loadedCount={logsData.entries.length}
            filteredCount={logsView.filteredCount}
            hasMoreOlder={logsData.hasMoreOlder}
            isLoadingOlder={logsData.isLoadingOlder}
            onScroll={logsView.handleScroll}
            onListKeyDown={logsView.handleListKeyDown}
            activeDescendantId={logsView.activeDescendantId}
            onSelectSeq={(seq) => logsView.setSelectedSeq(seq)}
          />
        </Col>
        <Col xl={4}>
          <LogsDetailsCard selectedEntry={logsView.selectedEntry} />
        </Col>
      </Row>
    </div>
  );
}
