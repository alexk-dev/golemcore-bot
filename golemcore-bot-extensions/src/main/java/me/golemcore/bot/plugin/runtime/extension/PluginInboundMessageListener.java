package me.golemcore.bot.plugin.runtime.extension;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.port.outbound.InboundMessageDispatchPort;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridges plugin API inbound message events into the host coordinator.
 */
@Component
@Slf4j
public class PluginInboundMessageListener {

    private final InboundMessageDispatchPort inboundMessageDispatchPort;
    private final PluginExtensionApiMapper mapper;

    public PluginInboundMessageListener(
            InboundMessageDispatchPort inboundMessageDispatchPort,
            PluginExtensionApiMapper mapper) {
        this.inboundMessageDispatchPort = inboundMessageDispatchPort;
        this.mapper = mapper;
    }

    @EventListener
    public void onInboundMessage(me.golemcore.plugin.api.extension.loop.AgentLoop.InboundMessageEvent event) {
        me.golemcore.bot.domain.model.Message message = mapper.toHostMessage(event.message());
        log.debug("[Inbound] enqueue plugin message (channel={}, chatId={})",
                message.getChannelType(), message.getChatId());
        inboundMessageDispatchPort.dispatch(message);
    }
}
