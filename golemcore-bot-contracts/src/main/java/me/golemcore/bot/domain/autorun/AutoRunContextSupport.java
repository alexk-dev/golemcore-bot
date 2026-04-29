package me.golemcore.bot.domain.autorun;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.support.StringValueSupport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared helpers for autonomous run metadata across scheduler, agent loop, and
 * dashboard-facing projections.
 */
public final class AutoRunContextSupport {

    private AutoRunContextSupport() {
    }

    public static boolean isAutoMessage(Message message) {
        if (message == null) {
            return false;
        }
        return isAutoMetadata(message.getMetadata());
    }

    public static boolean isAutoMetadata(Map<String, Object> metadata) {
        return metadata != null && Boolean.TRUE.equals(metadata.get(ContextAttributes.AUTO_MODE));
    }

    public static String readMetadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || StringValueSupport.isBlank(key)) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return null;
    }

    public static Map<String, Object> buildAutoMessageMetadata(AgentContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (context == null) {
            return metadata;
        }

        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.AUTO_MODE))) {
            metadata.put(ContextAttributes.AUTO_MODE, true);
        }

        copyStringAttribute(context, metadata, ContextAttributes.AUTO_RUN_KIND);
        copyStringAttribute(context, metadata, ContextAttributes.AUTO_RUN_ID);
        copyStringAttribute(context, metadata, ContextAttributes.AUTO_SCHEDULE_ID);
        copyStringAttribute(context, metadata, ContextAttributes.AUTO_GOAL_ID);
        copyStringAttribute(context, metadata, ContextAttributes.AUTO_TASK_ID);
        copyStringAttribute(context, metadata, ContextAttributes.AUTO_SCHEDULED_TASK_ID);
        copyStringAttribute(context, metadata, ContextAttributes.ACTIVE_SKILL_NAME);
        copyStringAttribute(context, metadata, ContextAttributes.AUTO_RUN_ACTIVE_SKILL);
        copyStringAttribute(context, metadata, ContextAttributes.AUTO_REFLECTION_TIER);
        copyStringAttribute(context, metadata, ContextAttributes.CONVERSATION_KEY);
        copyStringAttribute(context, metadata, ContextAttributes.TRANSPORT_CHAT_ID);
        copyStringAttribute(context, metadata, ContextAttributes.WEB_CLIENT_INSTANCE_ID);

        Boolean reflectionActive = context.getAttribute(ContextAttributes.AUTO_REFLECTION_ACTIVE);
        if (Boolean.TRUE.equals(reflectionActive)) {
            metadata.put(ContextAttributes.AUTO_REFLECTION_ACTIVE, true);
        }

        Boolean reflectionTierPriority = context.getAttribute(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY);
        if (reflectionTierPriority != null) {
            metadata.put(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, reflectionTierPriority);
        }

        copyStringAttribute(context, metadata, ContextAttributes.AUTO_RUN_STATUS);
        copyStringAttribute(context, metadata, ContextAttributes.AUTO_RUN_FINISH_REASON);
        copyStringAttribute(context, metadata, ContextAttributes.AUTO_RUN_FAILURE_SUMMARY);
        copyStringAttribute(context, metadata, ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT);
        copyStringAttribute(context, metadata, ContextAttributes.AUTO_RUN_ASSISTANT_TEXT);
        return metadata;
    }

    public static Map<String, String> buildMdcContext(
            String channelType,
            String conversationKey,
            String transportChatId,
            String scheduleId,
            String runId,
            String goalId,
            String taskId,
            String scheduledTaskId) {
        Map<String, String> context = new LinkedHashMap<>();
        putString(context, ContextAttributes.SESSION_IDENTITY_CHANNEL, channelType);
        putString(context, ContextAttributes.CONVERSATION_KEY, conversationKey);
        putString(context, ContextAttributes.TRANSPORT_CHAT_ID, transportChatId);
        putString(context, ContextAttributes.AUTO_SCHEDULE_ID, scheduleId);
        putString(context, ContextAttributes.AUTO_RUN_ID, runId);
        putString(context, ContextAttributes.AUTO_GOAL_ID, goalId);
        putString(context, ContextAttributes.AUTO_TASK_ID, taskId);
        putString(context, ContextAttributes.AUTO_SCHEDULED_TASK_ID, scheduledTaskId);
        return context;
    }

    public static Map<String, String> buildMdcContext(Message message) {
        if (message == null) {
            return Map.of();
        }

        Map<String, Object> metadata = message.getMetadata();
        return buildMdcContext(
                message.getChannelType(),
                readMetadataString(metadata, ContextAttributes.CONVERSATION_KEY),
                readMetadataString(metadata, ContextAttributes.TRANSPORT_CHAT_ID),
                readMetadataString(metadata, ContextAttributes.AUTO_SCHEDULE_ID),
                readMetadataString(metadata, ContextAttributes.AUTO_RUN_ID),
                readMetadataString(metadata, ContextAttributes.AUTO_GOAL_ID),
                readMetadataString(metadata, ContextAttributes.AUTO_TASK_ID),
                readMetadataString(metadata, ContextAttributes.AUTO_SCHEDULED_TASK_ID));
    }

    private static void copyStringAttribute(AgentContext context, Map<String, Object> target, String key) {
        String value = context.getAttribute(key);
        if (!StringValueSupport.isBlank(value)) {
            target.put(key, value);
        }
    }

    private static void putString(Map<String, String> target, String key, String value) {
        if (!StringValueSupport.isBlank(key) && !StringValueSupport.isBlank(value)) {
            target.put(key, value);
        }
    }
}
