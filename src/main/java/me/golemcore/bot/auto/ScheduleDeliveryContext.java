package me.golemcore.bot.auto;

/**
 * Active channel binding used for scheduled runs and report delivery.
 *
 * @param channelType
 *            logical channel type used by the session
 * @param sessionChatId
 *            chat identifier stored on the session
 * @param transportChatId
 *            chat identifier used by the outbound transport
 */
public record ScheduleDeliveryContext(String channelType,String sessionChatId,String transportChatId){

public static ScheduleDeliveryContext auto(){return new ScheduleDeliveryContext("auto","auto","auto");}}
