package me.golemcore.bot.adapter.outbound.hive;

import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.hive.HiveOutboxSummary;
import me.golemcore.bot.port.outbound.HiveEventOutboxPort;
import org.springframework.stereotype.Component;

@Component
public class HiveEventOutboxPortAdapter implements HiveEventOutboxPort {

    private final HiveEventOutboxService hiveEventOutboxService;
    private final HiveApiClient hiveApiClient;

    public HiveEventOutboxPortAdapter(
            HiveEventOutboxService hiveEventOutboxService,
            HiveApiClient hiveApiClient) {
        this.hiveEventOutboxService = hiveEventOutboxService;
        this.hiveApiClient = hiveApiClient;
    }

    @Override
    public HiveOutboxSummary getSummary() {
        HiveEventOutboxService.OutboxSummary summary = hiveEventOutboxService.getSummary();
        return new HiveOutboxSummary(summary.pendingBatchCount(), summary.pendingEventCount(), summary.lastError());
    }

    @Override
    public void flushPending(HiveSessionState sessionState) {
        hiveEventOutboxService.flush(sessionState, hiveApiClient::publishEventsBatch);
    }

    @Override
    public void clear() {
        hiveEventOutboxService.clear();
    }
}
