package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.hive.HiveOutboxSummary;

public interface HiveEventOutboxPort {

    HiveOutboxSummary getSummary();

    void flushPending(HiveSessionState sessionState);

    void clear();
}
