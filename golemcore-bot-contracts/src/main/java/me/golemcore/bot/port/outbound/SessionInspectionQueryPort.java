package me.golemcore.bot.port.outbound;

import java.util.List;
import java.util.Map;
import me.golemcore.bot.domain.view.SessionDetailView;
import me.golemcore.bot.domain.view.SessionMessagesPageView;
import me.golemcore.bot.domain.view.SessionSummaryView;
import me.golemcore.bot.domain.view.SessionTraceExportView;
import me.golemcore.bot.domain.view.SessionTraceSummaryView;
import me.golemcore.bot.domain.view.SessionTraceView;

/**
 * Session inspection operations exposed from the app runtime.
 */
public interface SessionInspectionQueryPort {

    List<SessionSummaryView> listSessions(String channel);

    SessionDetailView getSessionDetail(String sessionId);

    SessionMessagesPageView getSessionMessages(String sessionId, int limit, String beforeMessageId);

    SessionTraceSummaryView getSessionTraceSummary(String sessionId);

    SessionTraceView getSessionTrace(String sessionId);

    SessionTraceExportView getSessionTraceExport(String sessionId);

    Map<String, Object> getSessionTraceSnapshotPayload(String sessionId, String snapshotId);

    int compactSession(String sessionId, int keepLast);

    void clearSession(String sessionId);

    void deleteSession(String sessionId);
}
