package me.golemcore.bot.domain.model;

/**
 * Event for scheduling a proactive job completion notification.
 */
public record DelayedJobReadyEvent(String channelType,String conversationKey,String transportChatId,String jobId,String message,String artifactPath,String artifactName,String mimeType){}
