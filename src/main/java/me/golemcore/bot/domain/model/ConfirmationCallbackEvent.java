package me.golemcore.bot.domain.model;

/**
 * Event published when a user responds to a confirmation prompt (e.g. inline
 * keyboard button press in Telegram).
 *
 * <p>
 * Published by inbound channel adapters. Consumed by the confirmation adapter
 * to resolve pending confirmations and update the UI.
 *
 * @since 1.0
 */
public record ConfirmationCallbackEvent(String confirmationId,boolean approved,String chatId,String messageId){}
