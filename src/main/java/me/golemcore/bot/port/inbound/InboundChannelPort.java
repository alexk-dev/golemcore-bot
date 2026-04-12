package me.golemcore.bot.port.inbound;

import me.golemcore.bot.domain.model.Message;

import java.util.function.Consumer;

public interface InboundChannelPort {

    String getChannelType();

    void start();

    void stop();

    boolean isRunning();

    boolean isAuthorized(String senderId);

    void onMessage(Consumer<Message> handler);
}
