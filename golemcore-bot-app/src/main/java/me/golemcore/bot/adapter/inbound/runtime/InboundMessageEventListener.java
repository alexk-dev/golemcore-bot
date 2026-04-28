package me.golemcore.bot.adapter.inbound.runtime;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.session.SessionRunCoordinator;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class InboundMessageEventListener {

    private final SessionRunCoordinator coordinator;

    public InboundMessageEventListener(SessionRunCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @EventListener
    public void onInboundMessage(AgentLoop.InboundMessageEvent event) {
        Message message = event.message();
        log.debug("[Inbound] enqueue message (channel={}, chatId={})", message.getChannelType(), message.getChatId());
        coordinator.enqueue(message);
    }
}
