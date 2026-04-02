package me.golemcore.bot.auto;

/**
 * Active channel binding used for scheduled runs and report delivery.
 */
public record ScheduleDeliveryContext(String channelType,String sessionChatId,String transportChatId){

public static ScheduleDeliveryContext auto(){return new ScheduleDeliveryContext("auto","auto","auto");}}
