package me.golemcore.bot.adapter.outbound.hive;

import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.hive.HiveControlChannelStatusSnapshot;
import me.golemcore.bot.port.outbound.HiveControlChannelPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HiveControlChannelPortAdapter implements HiveControlChannelPort {

    private final HiveControlChannelClient hiveControlChannelClient;

    @Override
    public void connect(HiveSessionState sessionState, Consumer<HiveControlCommandEnvelope> commandConsumer) {
        hiveControlChannelClient.connect(sessionState, commandConsumer);
    }

    @Override
    public void disconnect(String reason) {
        hiveControlChannelClient.disconnect(reason);
    }

    @Override
    public HiveControlChannelStatusSnapshot getStatus() {
        HiveControlChannelStatus status = hiveControlChannelClient.getStatus();
        if (status == null) {
            return HiveControlChannelStatusSnapshot.disconnected();
        }
        return new HiveControlChannelStatusSnapshot(
                status.state(),
                status.connectedAt(),
                status.lastMessageAt(),
                status.lastError(),
                status.lastReceivedCommandId(),
                status.receivedCommandCount());
    }
}
