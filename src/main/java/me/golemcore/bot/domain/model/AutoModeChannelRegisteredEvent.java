package me.golemcore.bot.domain.model;

/**
 * Event published when auto mode is enabled and a channel registers for
 * milestone notifications.
 *
 * <p>
 * Published by CommandRouter when the user runs {@code /auto on}. Consumed by
 * AutoModeScheduler to register the channel for sending milestone
 * notifications.
 *
 * @param channelType
 *            channel type that registered for auto mode
 * @param sessionChatId
 *            chat identifier stored on the session
 * @param transportChatId
 *            chat identifier used by the transport adapter
 * @since 1.0
 */
public record AutoModeChannelRegisteredEvent(String channelType,String sessionChatId,String transportChatId){

public AutoModeChannelRegisteredEvent(String channelType,String chatId){this(channelType,chatId,chatId);}}
