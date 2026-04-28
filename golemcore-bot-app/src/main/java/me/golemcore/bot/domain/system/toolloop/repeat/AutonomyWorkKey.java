package me.golemcore.bot.domain.system.toolloop.repeat;

import java.util.Map;
import java.util.Optional;
import me.golemcore.bot.domain.model.ContextAttributes;

/**
 * Durable ledger identity for one autonomous goal or task.
 */
// @formatter:off
public record AutonomyWorkKey(String sessionKey, String goalId, String taskId, String scheduleId) {

    private static final String STORAGE_DIRECTORY = "auto";
    private static final String STORAGE_ROOT = STORAGE_DIRECTORY + "/tool-ledgers";
    private static final String UNKNOWN_SEGMENT = "unknown";

    public static Optional<AutonomyWorkKey> fromMetadata(Map<String, Object> metadata) {
        if (metadata == null || !Boolean.TRUE.equals(metadata.get(ContextAttributes.AUTO_MODE))) {
            return Optional.empty();
        }
        String sessionKey = readString(metadata, ContextAttributes.CONVERSATION_KEY);
        String goalId = readString(metadata, ContextAttributes.AUTO_GOAL_ID);
        String taskId = readString(metadata, ContextAttributes.AUTO_TASK_ID);
        String scheduleId = readString(metadata, ContextAttributes.AUTO_SCHEDULE_ID);
        if (isBlank(sessionKey) || (isBlank(goalId) && isBlank(taskId))) {
            return Optional.empty();
        }
        return Optional.of(new AutonomyWorkKey(sessionKey, goalId, taskId, scheduleId));
    }

    public String storagePath() {
        String sessionSegment = safeSegment(sessionKey);
        if (!isBlank(taskId)) {
            return STORAGE_ROOT + "/" + sessionSegment + "/tasks/" + safeSegment(taskId) + ".json";
        }
        return STORAGE_ROOT + "/" + sessionSegment + "/goals/" + safeSegment(goalId) + ".json";
    }

    public String storageDirectory() {
        return STORAGE_DIRECTORY;
    }

    public String storageFile() {
        String prefix = storageDirectory() + "/";
        String path = storagePath();
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private static String readString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safeSegment(String value) {
        String source = value == null ? UNKNOWN_SEGMENT : value.trim();
        StringBuilder out = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); index++) {
            appendSafeCharacter(out, source.charAt(index));
        }
        return out.isEmpty() ? UNKNOWN_SEGMENT : out.toString();
    }

    private static void appendSafeCharacter(StringBuilder out, char ch) {
        if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
            out.append(ch);
        } else {
            out.append('_');
        }
    }
}
// @formatter:on
