package me.golemcore.bot.port.outbound;

import java.util.function.Consumer;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.hive.HiveControlChannelStatusSnapshot;

public interface HiveControlChannelPort {

    void connect(HiveSessionState sessionState, Consumer<HiveControlCommandEnvelope> commandConsumer);

    void disconnect(String reason);

    HiveControlChannelStatusSnapshot getStatus();
}
