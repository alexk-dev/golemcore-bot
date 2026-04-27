package me.golemcore.bot.adapter.outbound.hive;

import java.util.function.Consumer;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.hive.HiveControlChannelStatusSnapshot;
import me.golemcore.bot.port.outbound.HiveControlChannelPort;
import org.springframework.stereotype.Component;

@Component
public class HiveControlChannelPortAdapter implements HiveControlChannelPort {

    private final HiveControlChannelClient hiveControlChannelClient;

    public HiveControlChannelPortAdapter(HiveControlChannelClient hiveControlChannelClient) {
        this.hiveControlChannelClient = hiveControlChannelClient;
    }

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
        HiveControlChannelStatusSnapshot status = hiveControlChannelClient.getStatus();
        return status != null ? status : HiveControlChannelStatusSnapshot.disconnected();
    }
}
