package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.Message;

public interface InboundMessageDispatchPort {

    void dispatch(Message message);
}
