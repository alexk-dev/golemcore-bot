package me.golemcore.bot.port.outbound;

import java.time.Instant;
import java.util.function.Consumer;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveSessionState;

public interface HiveControlChannelPort {

    void connect(HiveSessionState sessionState, Consumer<HiveControlCommandEnvelope> commandConsumer);

    void disconnect(String reason);

    ControlChannelStatus getStatus();

    record ControlChannelStatus(
            String state,
            Instant connectedAt,
            Instant lastMessageAt,
            String lastError,
            String lastReceivedCommandId,
            int receivedCommandCount) {

        public static ControlChannelStatus disconnected() {
            return new ControlChannelStatus("DISCONNECTED", null, null, null, null, 0);
        }
    }
}
