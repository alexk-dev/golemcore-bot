package me.golemcore.bot.adapter.outbound.hive;

import java.util.function.Consumer;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveSessionState;

public interface HiveControlChannelClient {

    void connect(HiveSessionState sessionState, Consumer<HiveControlCommandEnvelope> commandConsumer);

    void disconnect(String reason);

    HiveControlChannelStatus getStatus();
}
