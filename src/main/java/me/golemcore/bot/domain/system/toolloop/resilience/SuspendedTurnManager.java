package me.golemcore.bot.domain.system.toolloop.resilience;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * L5 — Cold Retry via suspended turns.
 *
 * <p>
 * When L1–L4 are exhausted, the turn is not failed permanently. Instead, this
 * manager suspends it by scheduling a {@link DelayedSessionAction} with kind
 * {@code RETRY_LLM_TURN}. The existing delayed action infrastructure handles
 * persistence (file-based JSON), lease-based polling, and dead-letter
 * promotion.
 *
 * <p>
 * The retry schedule uses exponential backoff: 2m → 5m → 15m → 1h (capped),
 * with a maximum of 4 attempts per day. After all attempts are exhausted, the
 * action lands in DEAD_LETTER and the user receives a final notification.
 *
 * <p>
 * The suspended turn payload includes:
 * <ul>
 * <li>sessionId — to resume the correct conversation</li>
 * <li>channelType + chatId — to notify the user</li>
 * <li>errorCode — last LLM error code for diagnostics</li>
 * <li>originalPrompt — the user's original message (if available)</li>
 * <li>suspendedAt — timestamp for audit</li>
 * </ul>
 *
 * <p>
 * When the delayed action fires (via
 * {@code DelayedSessionActionService.leaseDueActions}), the turn is replayed
 * through the normal AgentLoop pipeline. If it succeeds, the user receives the
 * response as if nothing happened. If it fails again, the backoff increases and
 * the user is kept informed.
 */
public class SuspendedTurnManager {

    private static final Logger log = LoggerFactory.getLogger(SuspendedTurnManager.class);

    private static final long[] BACKOFF_SCHEDULE_SECONDS = {
            120, // 2 minutes
            300, // 5 minutes
            900, // 15 minutes
            3600 // 1 hour
    };

    private final DelayedSessionActionService actionService;
    private final Clock clock;

    public SuspendedTurnManager(DelayedSessionActionService actionService, Clock clock) {
        this.actionService = actionService;
        this.clock = clock;
    }

    /**
     * Suspends the current turn by scheduling a delayed retry action.
     *
     * @param context
     *            the agent context (provides session, channel info)
     * @param errorCode
     *            the LLM error code that triggered suspension
     * @param config
     *            resilience configuration
     * @return user-facing message explaining what happened
     */
    public String suspend(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
        Instant now = clock.instant();
        int maxAttempts = config.getColdRetryMaxAttempts();
        long delaySeconds = computeDelay(0);

        String sessionId = context.getSession() != null ? context.getSession().getId() : null;
        String chatId = context.getSession() != null ? context.getSession().getChatId() : null;
        String channelType = context.getSession() != null
                ? String.valueOf(context.getSession().getMetadata().getOrDefault("channelType", "unknown"))
                : "unknown";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errorCode", errorCode);
        payload.put("suspendedAt", now.toString());
        payload.put("resumeAttempt", 0);
        if (sessionId != null) {
            payload.put("sessionId", sessionId);
        }

        String dedupeKey = "resilience-cold-retry:" + (sessionId != null ? sessionId : chatId);

        DelayedSessionAction action = DelayedSessionAction.builder()
                .id(UUID.randomUUID().toString())
                .channelType(channelType)
                .conversationKey(sessionId)
                .transportChatId(chatId)
                .kind(DelayedActionKind.RETRY_LLM_TURN)
                .runAt(now.plusSeconds(delaySeconds))
                .maxAttempts(maxAttempts)
                .dedupeKey(dedupeKey)
                .cancelOnUserActivity(true)
                .createdBy("LlmResilienceOrchestrator")
                .createdAt(now)
                .updatedAt(now)
                .payload(payload)
                .build();

        actionService.schedule(action);
        log.info("[Resilience] L5 scheduled cold retry: id={}, runAt=+{}s, maxAttempts={}, session={}",
                action.getId(), delaySeconds, maxAttempts, sessionId);

        long delayMinutes = delaySeconds / 60;
        return String.format(
                "⏳ LLM providers are temporarily unavailable (error: %s). "
                        + "Your task has been saved and I'll retry automatically in %d minute(s). "
                        + "Send any message to cancel the retry and proceed manually.",
                errorCode, delayMinutes);
    }

    /**
     * Computes the delay for a given retry attempt using the backoff schedule.
     */
    public long computeDelay(int attempt) {
        int index = Math.min(attempt, BACKOFF_SCHEDULE_SECONDS.length - 1);
        return BACKOFF_SCHEDULE_SECONDS[index];
    }

    /**
     * Called by the delayed action handler when a cold retry fires. Increments the
     * attempt counter in the payload for the next retry.
     *
     * @param action
     *            the delayed action being executed
     * @return the updated resume attempt number
     */
    public int prepareResume(DelayedSessionAction action) {
        Map<String, Object> payload = action.getPayload();
        int resumeAttempt = 0;
        Object existing = payload.get("resumeAttempt");
        if (existing instanceof Number number) {
            resumeAttempt = number.intValue();
        }
        resumeAttempt++;
        payload.put("resumeAttempt", resumeAttempt);

        long nextDelay = computeDelay(resumeAttempt);
        action.setRunAt(clock.instant().plusSeconds(nextDelay));
        action.setUpdatedAt(clock.instant());
        return resumeAttempt;
    }
}
