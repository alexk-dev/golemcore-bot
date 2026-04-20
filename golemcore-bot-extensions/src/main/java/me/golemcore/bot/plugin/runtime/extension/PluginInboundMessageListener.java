package me.golemcore.bot.plugin.runtime.extension;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.port.outbound.InboundMessageDispatchPort;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridges plugin API inbound message events into the host coordinator.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PluginInboundMessageListener {

    private final InboundMessageDispatchPort inboundMessageDispatchPort;
    private final PluginExtensionApiMapper mapper;

    @EventListener
    public void onInboundMessage(me.golemcore.plugin.api.extension.loop.AgentLoop.InboundMessageEvent event) {
        me.golemcore.bot.domain.model.Message message = mapper.toHostMessage(event.message());
        log.debug("[Inbound] enqueue plugin message (channel={}, chatId={})",
                message.getChannelType(), message.getChatId());
        inboundMessageDispatchPort.dispatch(message);
    }
}
