package me.golemcore.bot.domain.model;

/**
 * Event published when a user responds to a plan approval prompt (e.g. inline
 * keyboard button press in Telegram). Actions: "approve" or "cancel".
 *
 * @param planId
 *            plan awaiting approval
 * @param action
 *            approval action such as {@code approve} or {@code cancel}
 * @param chatId
 *            chat that sent the callback
 * @param messageId
 *            callback message identifier
 * @since 1.0
 */
public record PlanApprovalCallbackEvent(String planId,String action,String chatId,String messageId){}
