package me.golemcore.bot.port.outbound;

import java.util.List;
import me.golemcore.bot.domain.view.SessionDetailView;
import me.golemcore.bot.domain.view.SessionMessagesPageView;
import me.golemcore.bot.domain.view.SessionSummaryView;
import me.golemcore.bot.domain.view.SessionTraceExportView;
import me.golemcore.bot.domain.view.SessionTraceSummaryView;
import me.golemcore.bot.domain.view.SessionTraceView;

public interface HiveInspectionPayloadPort {

    Object toSessionListPayload(List<SessionSummaryView> sessions);

    Object toSessionDetailPayload(SessionDetailView sessionDetail);

    Object toSessionMessagesPayload(SessionMessagesPageView messagesPage);

    Object toSessionTraceSummaryPayload(SessionTraceSummaryView traceSummary);

    Object toSessionTracePayload(SessionTraceView trace);

    Object toSessionTraceExportPayload(SessionTraceExportView traceExport);
}
