package me.golemcore.bot.domain.model;

/**
 * Event published when a plan transitions to READY status and needs user
 * approval. Consumed by the Telegram plan approval adapter to display an inline
 * keyboard with approve/cancel buttons.
 *
 * @since 1.0
 */
public record PlanReadyEvent(String planId,String chatId){}
