package me.golemcore.bot.adapter.outbound.hive;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.port.outbound.HiveEventOutboxPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HiveEventOutboxPortAdapter implements HiveEventOutboxPort {

    private final HiveApiClient hiveApiClient;
    private final HiveEventOutboxService hiveEventOutboxService;

    @Override
    public OutboxSummary getSummary() {
        return toSummary(hiveEventOutboxService.getSummary());
    }

    @Override
    public OutboxSummary flush(HiveSessionState sessionState) {
        return toSummary(hiveEventOutboxService.flush(
                sessionState,
                (serverUrl, golemId, accessToken, events) -> hiveApiClient.publishEventsBatch(
                        serverUrl,
                        golemId,
                        accessToken,
                        events)));
    }

    @Override
    public void clear() {
        hiveEventOutboxService.clear();
    }

    private OutboxSummary toSummary(HiveEventOutboxService.OutboxSummary summary) {
        return new OutboxSummary(summary.pendingBatchCount(), summary.pendingEventCount(), summary.lastError());
    }
}
