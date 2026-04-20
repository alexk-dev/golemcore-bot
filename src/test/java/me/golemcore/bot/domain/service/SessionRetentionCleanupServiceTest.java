package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRetentionCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-19T12:00:00Z");

    private SessionPort sessionPort;
    private RuntimeConfigService runtimeConfigService;
    private ActiveSessionPointerService activeSessionPointerService;
    private PlanService planService;
    private DelayedSessionActionService delayedSessionActionService;
    private SessionRetentionCleanupService service;

    @BeforeEach
    void setUp() {
        sessionPort = mock(SessionPort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        activeSessionPointerService = mock(ActiveSessionPointerService.class);
        planService = mock(PlanService.class);
        delayedSessionActionService = mock(DelayedSessionActionService.class);
        service = new SessionRetentionCleanupService(
                sessionPort,
                runtimeConfigService,
                activeSessionPointerService,
                planService,
                delayedSessionActionService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(runtimeConfigService.isSessionRetentionEnabled()).thenReturn(true);
        when(runtimeConfigService.getSessionRetentionMaxAge()).thenReturn(Duration.ofDays(30));
        when(runtimeConfigService.isSessionRetentionProtectActiveSessions()).thenReturn(true);
        when(runtimeConfigService.isSessionRetentionProtectSessionsWithPlans()).thenReturn(true);
        when(runtimeConfigService.isSessionRetentionProtectSessionsWithDelayedActions()).thenReturn(true);
        when(activeSessionPointerService.getPointersSnapshot()).thenReturn(Map.of());
        when(sessionPort.cleanupExpiredSessions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
    }

    @Test
    void shouldSkipCleanupWhenFeatureDisabled() {
        when(runtimeConfigService.isSessionRetentionEnabled()).thenReturn(false);

        SessionRetentionCleanupService.SessionRetentionCleanupResult result = service.cleanupExpiredSessions();

        assertFalse(result.isEnabled());
        assertEquals(0, result.getDeletedCount());
        verify(sessionPort, never()).cleanupExpiredSessions(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldNotResolvePointerSnapshotWhenFeatureDisabled() {
        when(runtimeConfigService.isSessionRetentionEnabled()).thenReturn(false);

        service.cleanupExpiredSessions();

        verify(activeSessionPointerService, never()).getPointersSnapshot();
    }

    @Test
    void shouldDeleteExpiredSessionsWhenNoProtectionMatches() {
        when(sessionPort.cleanupExpiredSessions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Instant cutoff = invocation.getArgument(0);
                    @SuppressWarnings("unchecked")
                    java.util.function.Predicate<AgentSession> predicate = invocation.getArgument(1);
                    AgentSession expired = session("telegram:old", ChannelTypes.TELEGRAM, "old",
                            NOW.minus(Duration.ofDays(40)));
                    AgentSession fresh = session("telegram:fresh", ChannelTypes.TELEGRAM, "fresh",
                            NOW.minus(Duration.ofDays(5)));
                    int deleted = 0;
                    for (AgentSession candidate : List.of(expired, fresh)) {
                        Instant updatedAt = candidate.getUpdatedAt();
                        if (updatedAt != null && updatedAt.isBefore(cutoff) && !predicate.test(candidate)) {
                            deleted++;
                        }
                    }
                    return deleted;
                });

        SessionRetentionCleanupService.SessionRetentionCleanupResult result = service.cleanupExpiredSessions();

        assertTrue(result.isEnabled());
        assertEquals(1, result.getDeletedCount());
        assertEquals(NOW.minus(Duration.ofDays(30)), result.getCutoff());
    }

    @Test
    void shouldRetainPointerPlanAndDelayedActionSessions() {
        when(activeSessionPointerService.getPointersSnapshot()).thenReturn(new LinkedHashMap<>(Map.of(
                "telegram|100", "active-conv",
                "web|admin|client-1", "webconv1")));
        when(planService.hasActivePlans(new SessionIdentity(ChannelTypes.WEB, "plan-conv")))
                .thenReturn(true);
        when(delayedSessionActionService.hasPendingActions(ChannelTypes.TELEGRAM, "delayed-conv"))
                .thenReturn(true);
        AgentSession active = session("telegram:active-conv", ChannelTypes.TELEGRAM, "active-conv",
                NOW.minus(Duration.ofDays(90)));
        AgentSession plan = session("web:plan-conv", ChannelTypes.WEB, "plan-conv",
                NOW.minus(Duration.ofDays(90)));
        AgentSession delayed = session("telegram:delayed-conv", ChannelTypes.TELEGRAM, "delayed-conv",
                NOW.minus(Duration.ofDays(90)));
        AgentSession expired = session("telegram:expired", ChannelTypes.TELEGRAM, "expired",
                NOW.minus(Duration.ofDays(90)));
        List<AgentSession> candidates = List.of(active, plan, delayed, expired);
        when(sessionPort.cleanupExpiredSessions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Predicate<AgentSession> predicate = invocation.getArgument(1);
                    int deleted = 0;
                    for (AgentSession candidate : candidates) {
                        if (!predicate.test(candidate)) {
                            deleted++;
                        }
                    }
                    return deleted;
                });

        SessionRetentionCleanupService.SessionRetentionCleanupResult result = service.cleanupExpiredSessions();

        assertEquals(1, result.getDeletedCount());
        assertEquals(2, result.getProtectedActiveSessionCount());
        verify(planService).hasActivePlans(new SessionIdentity(ChannelTypes.WEB, "plan-conv"));
        verify(delayedSessionActionService).hasPendingActions(ChannelTypes.TELEGRAM, "delayed-conv");
    }

    private AgentSession session(String id, String channelType, String chatId, Instant updatedAt) {
        return AgentSession.builder()
                .id(id)
                .channelType(channelType)
                .chatId(chatId)
                .createdAt(updatedAt.minus(Duration.ofHours(1)))
                .updatedAt(updatedAt)
                .metadata(new LinkedHashMap<>())
                .build();
    }
}
