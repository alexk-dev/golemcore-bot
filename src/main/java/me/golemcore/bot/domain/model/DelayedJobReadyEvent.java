package me.golemcore.bot.domain.model;

/**
 * Event for scheduling a proactive job completion notification.
 *
 * @param channelType
 *            channel type for the notification
 * @param conversationKey
 *            logical conversation key
 * @param transportChatId
 *            transport-level chat identifier
 * @param jobId
 *            completed delayed job identifier
 * @param message
 *            notification message
 * @param artifactPath
 *            optional artifact path
 * @param artifactName
 *            optional artifact display name
 * @param mimeType
 *            optional artifact MIME type
 */
public record DelayedJobReadyEvent(String channelType,String conversationKey,String transportChatId,String jobId,String message,String artifactPath,String artifactName,String mimeType){}
