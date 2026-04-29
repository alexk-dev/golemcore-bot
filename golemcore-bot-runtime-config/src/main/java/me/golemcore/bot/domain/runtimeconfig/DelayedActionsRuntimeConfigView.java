package me.golemcore.bot.domain.runtimeconfig;

import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_ALLOW_RUN_LATER;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_LEASE_DURATION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_MAX_ATTEMPTS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_MAX_DELAY;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_MAX_PENDING_PER_SESSION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_RETENTION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_TICK_SECONDS;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import me.golemcore.bot.domain.model.RuntimeConfig;

public interface DelayedActionsRuntimeConfigView extends RuntimeConfigSource {
    default boolean isDelayedActionsEnabled() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null) {
            return DEFAULT_DELAYED_ACTIONS_ENABLED;
        }
        Boolean val = delayedConfig.getEnabled();
        return val != null ? val : DEFAULT_DELAYED_ACTIONS_ENABLED;
    }

    default int getDelayedActionsTickSeconds() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null) {
            return DEFAULT_DELAYED_ACTIONS_TICK_SECONDS;
        }
        Integer val = delayedConfig.getTickSeconds();
        return val != null && val > 0 ? val : DEFAULT_DELAYED_ACTIONS_TICK_SECONDS;
    }

    default int getDelayedActionsMaxPendingPerSession() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null) {
            return DEFAULT_DELAYED_ACTIONS_MAX_PENDING_PER_SESSION;
        }
        Integer val = delayedConfig.getMaxPendingPerSession();
        return val != null && val > 0 ? Math.min(val, DEFAULT_DELAYED_ACTIONS_MAX_PENDING_PER_SESSION)
                : DEFAULT_DELAYED_ACTIONS_MAX_PENDING_PER_SESSION;
    }

    default Duration getDelayedActionsMaxDelay() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null || delayedConfig.getMaxDelay() == null || delayedConfig.getMaxDelay().isBlank()) {
            return DEFAULT_DELAYED_ACTIONS_MAX_DELAY;
        }
        try {
            return Duration.parse(delayedConfig.getMaxDelay());
        } catch (DateTimeParseException e) {
            return DEFAULT_DELAYED_ACTIONS_MAX_DELAY;
        }
    }

    default int getDelayedActionsDefaultMaxAttempts() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null) {
            return DEFAULT_DELAYED_ACTIONS_MAX_ATTEMPTS;
        }
        Integer val = delayedConfig.getDefaultMaxAttempts();
        return val != null && val > 0 ? val : DEFAULT_DELAYED_ACTIONS_MAX_ATTEMPTS;
    }

    default Duration getDelayedActionsLeaseDuration() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null || delayedConfig.getLeaseDuration() == null
                || delayedConfig.getLeaseDuration().isBlank()) {
            return DEFAULT_DELAYED_ACTIONS_LEASE_DURATION;
        }
        try {
            return Duration.parse(delayedConfig.getLeaseDuration());
        } catch (DateTimeParseException e) {
            return DEFAULT_DELAYED_ACTIONS_LEASE_DURATION;
        }
    }

    default Duration getDelayedActionsRetentionAfterCompletion() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null || delayedConfig.getRetentionAfterCompletion() == null
                || delayedConfig.getRetentionAfterCompletion().isBlank()) {
            return DEFAULT_DELAYED_ACTIONS_RETENTION;
        }
        try {
            return Duration.parse(delayedConfig.getRetentionAfterCompletion());
        } catch (DateTimeParseException e) {
            return DEFAULT_DELAYED_ACTIONS_RETENTION;
        }
    }

    default boolean isDelayedActionsRunLaterEnabled() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null) {
            return DEFAULT_DELAYED_ACTIONS_ALLOW_RUN_LATER;
        }
        Boolean val = delayedConfig.getAllowRunLater();
        return val != null ? val : DEFAULT_DELAYED_ACTIONS_ALLOW_RUN_LATER;
    }
}
