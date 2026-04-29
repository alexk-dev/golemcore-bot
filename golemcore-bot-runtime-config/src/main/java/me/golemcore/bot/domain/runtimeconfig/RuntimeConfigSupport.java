package me.golemcore.bot.domain.runtimeconfig;

import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.COMPACTION_TRIGGER_MODE_TOKEN_THRESHOLD;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_COMPACTION_TRIGGER_MODE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_STT_PROVIDER;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_QUEUE_STEERING_MODE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_WHISPER_STT_PROVIDER;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.LEGACY_ELEVENLABS_PROVIDER;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.LEGACY_WHISPER_PROVIDER;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import me.golemcore.bot.domain.model.ModelTierCatalog;

final class RuntimeConfigSupport {

    private RuntimeConfigSupport() {
    }

    static boolean isValidDuration(String value) {
        try {
            Duration parsed = Duration.parse(value);
            return parsed != null;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static String normalizeNonBlankString(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    static String normalizeOptionalModelTier(String value) {
        String normalizedTierId = ModelTierCatalog.normalizeTierId(value);
        if (normalizedTierId == null || "default".equals(normalizedTierId)) {
            return null;
        }
        return normalizedTierId;
    }

    static String normalizeQueueMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return DEFAULT_TURN_QUEUE_STEERING_MODE;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(normalized)) {
            return "all";
        }
        if ("one-at-a-time".equals(normalized) || "one_at_a_time".equals(normalized) || "one-at-time".equals(normalized)
                || "single".equals(normalized)) {
            return "one-at-a-time";
        }
        return DEFAULT_TURN_QUEUE_STEERING_MODE;
    }

    static String normalizeVoiceProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (LEGACY_ELEVENLABS_PROVIDER.equals(normalized)) {
            return DEFAULT_STT_PROVIDER;
        }
        if (LEGACY_WHISPER_PROVIDER.equals(normalized)) {
            return DEFAULT_WHISPER_STT_PROVIDER;
        }
        return normalized;
    }

    static String normalizeCompactionTriggerMode(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_COMPACTION_TRIGGER_MODE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case DEFAULT_COMPACTION_TRIGGER_MODE, COMPACTION_TRIGGER_MODE_TOKEN_THRESHOLD -> normalized;
            default -> DEFAULT_COMPACTION_TRIGGER_MODE;
        };
    }

    static String normalizeUtcTimeValue(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            LocalTime parsed = LocalTime.parse(value.trim());
            return parsed.withSecond(0).withNano(0).toString();
        } catch (DateTimeException e) {
            return defaultValue;
        }
    }
}
