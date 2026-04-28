package me.golemcore.bot.domain.runtimeconfig;

import java.time.Duration;

public interface TurnRuntimeConfigView {
    int getTurnMaxSkillTransitions();

    int getTurnMaxLlmCalls();

    int getTurnMaxToolExecutions();

    int getToolLoopMaxLlmCalls();

    int getToolLoopMaxToolExecutions();

    Duration getTurnDeadline();

    boolean isTurnAutoRetryEnabled();

    int getTurnAutoRetryMaxAttempts();

    long getTurnAutoRetryBaseDelayMs();

    boolean isTurnQueueSteeringEnabled();

    String getTurnQueueSteeringMode();

    String getTurnQueueFollowUpMode();
}
