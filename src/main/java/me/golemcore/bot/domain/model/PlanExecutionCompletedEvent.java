package me.golemcore.bot.domain.model;

/**
 * Event published when plan execution completes (either fully or partially).
 * Consumed by channel adapters to send execution summaries to the user.
 *
 * @param planId
 *            completed plan identifier
 * @param chatId
 *            chat that should receive the completion summary
 * @param summary
 *            user-facing execution summary
 * @since 1.0
 */
public record PlanExecutionCompletedEvent(String planId,String chatId,String summary){}
