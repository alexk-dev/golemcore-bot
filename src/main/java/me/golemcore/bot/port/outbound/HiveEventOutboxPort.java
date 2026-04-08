package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.HiveSessionState;

public interface HiveEventOutboxPort {

    OutboxSummary getSummary();

    OutboxSummary flush(HiveSessionState sessionState);

    void clear();

    record OutboxSummary(int pendingBatchCount, int pendingEventCount, String lastError) {
    }
}
