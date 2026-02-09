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
 * @since 1.0
 */
public record AutoModeChannelRegisteredEvent(String channelType,String chatId){}
