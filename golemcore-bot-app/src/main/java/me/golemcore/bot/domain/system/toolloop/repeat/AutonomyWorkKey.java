package me.golemcore.bot.domain.system.toolloop.repeat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import me.golemcore.bot.domain.model.ContextAttributes;

/**
 * Durable ledger identity for one autonomous goal or task.
 */
// @formatter:off
public record AutonomyWorkKey(
        String sessionKey,
        String goalId,
        String taskId,
        String scheduleId) {

    private static final String STORAGE_DIRECTORY = "auto";
    private static final String STORAGE_ROOT = STORAGE_DIRECTORY + "/tool-ledgers";
    private static final String UNKNOWN_SEGMENT = "unknown";
    private static final int MAX_READABLE_SEGMENT_LENGTH = 64;

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
        String sessionSegment = hashedSegment(sessionKey, sessionKey);
        if (!isBlank(taskId)) {
            String taskIdentity = valueOrUnknown(goalId) + ":" + valueOrUnknown(taskId);
            return STORAGE_ROOT + "/" + sessionSegment + "/tasks/" + hashedSegment(taskId, taskIdentity) + ".json";
        }
        return STORAGE_ROOT + "/" + sessionSegment + "/goals/" + hashedSegment(goalId, goalId) + ".json";
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
        String source = valueOrUnknown(value);
        StringBuilder out = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); index++) {
            appendSafeCharacter(out, source.charAt(index));
        }
        String safe = out.isEmpty() ? UNKNOWN_SEGMENT : out.toString();
        return safe.length() <= MAX_READABLE_SEGMENT_LENGTH
                ? safe
                : safe.substring(0, MAX_READABLE_SEGMENT_LENGTH);
    }

    private static String hashedSegment(String displayValue, String identityValue) {
        return safeSegment(displayValue) + "-" + shortHash(valueOrUnknown(identityValue));
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN_SEGMENT : value.trim();
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                builder.append(String.format("%02x", bytes[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
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
