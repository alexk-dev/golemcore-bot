package me.golemcore.bot.adapter.outbound.event;

import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.infrastructure.event.SpringEventBus;
import me.golemcore.bot.port.outbound.InboundMessageDispatchPort;
import org.springframework.stereotype.Component;

@Component
public class SpringInboundMessageDispatchAdapter implements InboundMessageDispatchPort {

    private final SpringEventBus springEventBus;

    public SpringInboundMessageDispatchAdapter(SpringEventBus springEventBus) {
        this.springEventBus = springEventBus;
    }

    @Override
    public void dispatch(Message message) {
        springEventBus.publish(new AgentLoop.InboundMessageEvent(message));
    }
}
