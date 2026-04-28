package me.golemcore.bot.domain.sessions;

import me.golemcore.bot.domain.identity.SessionIdentitySupport;

import me.golemcore.bot.domain.support.StringValueSupport;

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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.port.outbound.SessionDelayedActionProtectionPort;
import me.golemcore.bot.port.outbound.SessionPlanProtectionPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.SessionRetentionRuntimeConfigPort;

@Slf4j
public class SessionRetentionCleanupService {

    private static final String LOG_PREFIX = "[SessionRetention]";

    private final SessionPort sessionPort;
    private final SessionRetentionRuntimeConfigPort runtimeConfigService;
    private final ActiveSessionPointerService activeSessionPointerService;
    private final List<SessionPlanProtectionPort> planProtectionPorts;
    private final List<SessionDelayedActionProtectionPort> delayedActionProtectionPorts;
    private final Clock clock;

    public SessionRetentionCleanupService(SessionPort sessionPort,
            SessionRetentionRuntimeConfigPort runtimeConfigService,
            ActiveSessionPointerService activeSessionPointerService,
            List<SessionPlanProtectionPort> planProtectionPorts,
            List<SessionDelayedActionProtectionPort> delayedActionProtectionPorts, Clock clock) {
        this.sessionPort = sessionPort;
        this.runtimeConfigService = runtimeConfigService;
        this.activeSessionPointerService = activeSessionPointerService;
        this.planProtectionPorts = planProtectionPorts;
        this.delayedActionProtectionPorts = delayedActionProtectionPorts;
        this.clock = clock;
    }

    public SessionRetentionCleanupResult cleanupExpiredSessions() {
        boolean enabled = runtimeConfigService.isSessionRetentionEnabled();
        Duration maxAge = runtimeConfigService.getSessionRetentionMaxAge();
        Instant now = clock.instant();
        Instant cutoff = now.minus(maxAge);
        boolean protectActiveSessions = runtimeConfigService.isSessionRetentionProtectActiveSessions();
        boolean protectSessionsWithPlans = runtimeConfigService.isSessionRetentionProtectSessionsWithPlans();
        boolean protectSessionsWithDelayedActions = runtimeConfigService
                .isSessionRetentionProtectSessionsWithDelayedActions();

        if (!enabled) {
            return SessionRetentionCleanupResult.builder().enabled(false).cutoff(cutoff).maxAge(maxAge).deletedCount(0)
                    .protectedActiveSessionCount(0).protectActiveSessions(protectActiveSessions)
                    .protectSessionsWithPlans(protectSessionsWithPlans)
                    .protectSessionsWithDelayedActions(protectSessionsWithDelayedActions).build();
        }

        Set<String> protectedActiveSessionKeys = protectActiveSessions ? resolveProtectedActiveSessionKeys() : Set.of();
        int deletedCount = sessionPort.cleanupExpiredSessions(cutoff, session -> shouldRetainSession(session,
                protectedActiveSessionKeys, protectSessionsWithPlans, protectSessionsWithDelayedActions));

        if (deletedCount > 0) {
            log.info("{} Deleted {} expired sessions older than {}", LOG_PREFIX, deletedCount, maxAge);
        } else {
            log.debug("{} No expired sessions eligible for cleanup (maxAge={})", LOG_PREFIX, maxAge);
        }

        return SessionRetentionCleanupResult.builder().enabled(true).cutoff(cutoff).maxAge(maxAge)
                .deletedCount(deletedCount).protectedActiveSessionCount(protectedActiveSessionKeys.size())
                .protectActiveSessions(protectActiveSessions).protectSessionsWithPlans(protectSessionsWithPlans)
                .protectSessionsWithDelayedActions(protectSessionsWithDelayedActions).build();
    }

    private boolean shouldRetainSession(AgentSession session, Set<String> protectedActiveSessionKeys,
            boolean protectSessionsWithPlans, boolean protectSessionsWithDelayedActions) {
        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(session);
        if (sessionIdentity == null || !sessionIdentity.isValid()) {
            return false;
        }

        if (protectedActiveSessionKeys.contains(sessionIdentity.asKey())) {
            return true;
        }
        if (protectSessionsWithPlans && hasActivePlans(sessionIdentity)) {
            return true;
        }
        if (protectSessionsWithDelayedActions && hasPendingDelayedActions(sessionIdentity)) {
            return true;
        }
        return false;
    }

    private boolean hasActivePlans(SessionIdentity sessionIdentity) {
        for (SessionPlanProtectionPort planProtectionPort : planProtectionPorts) {
            if (planProtectionPort.hasActivePlans(sessionIdentity)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingDelayedActions(SessionIdentity sessionIdentity) {
        for (SessionDelayedActionProtectionPort delayedActionProtectionPort : delayedActionProtectionPorts) {
            if (delayedActionProtectionPort.hasPendingActions(sessionIdentity.channelType(),
                    sessionIdentity.conversationKey())) {
                return true;
            }
        }
        return false;
    }

    private Set<String> resolveProtectedActiveSessionKeys() {
        Map<String, String> pointers = activeSessionPointerService.getPointersSnapshot();
        Set<String> protectedKeys = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : pointers.entrySet()) {
            if (StringValueSupport.isBlank(entry.getValue())) {
                continue;
            }
            String channelType = ActiveSessionPointerService.extractChannelType(entry.getKey()).orElse(null);
            if (channelType == null) {
                continue;
            }
            SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(channelType,
                    entry.getValue());
            if (sessionIdentity != null && sessionIdentity.isValid()) {
                protectedKeys.add(sessionIdentity.asKey());
            }
        }
        return protectedKeys;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SessionRetentionCleanupResult {
        private boolean enabled;
        private Instant cutoff;
        private Duration maxAge;
        private int deletedCount;
        private int protectedActiveSessionCount;
        private boolean protectActiveSessions;
        private boolean protectSessionsWithPlans;
        private boolean protectSessionsWithDelayedActions;
    }
}
