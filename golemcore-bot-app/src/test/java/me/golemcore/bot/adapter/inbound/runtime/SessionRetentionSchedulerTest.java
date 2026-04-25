package me.golemcore.bot.adapter.inbound.runtime;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionRetentionCleanupService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRetentionSchedulerTest {

    @Test
    void shouldRunCleanupWhenEnabledAndIntervalElapsed() {
        SessionRetentionCleanupService cleanupService = mock(SessionRetentionCleanupService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isSessionRetentionEnabled()).thenReturn(true);
        when(runtimeConfigService.getSessionRetentionCleanupInterval()).thenReturn(Duration.ofHours(24));

        SessionRetentionScheduler scheduler = new SessionRetentionScheduler(
                cleanupService,
                runtimeConfigService,
                Clock.fixed(Instant.parse("2026-04-19T12:00:00Z"), ZoneOffset.UTC));

        scheduler.tick();
        scheduler.tick();

        verify(cleanupService, times(1)).cleanupExpiredSessions();
    }

    @Test
    void shouldSkipCleanupWhenFeatureDisabled() {
        SessionRetentionCleanupService cleanupService = mock(SessionRetentionCleanupService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isSessionRetentionEnabled()).thenReturn(false);

        SessionRetentionScheduler scheduler = new SessionRetentionScheduler(
                cleanupService,
                runtimeConfigService,
                Clock.fixed(Instant.parse("2026-04-19T12:00:00Z"), ZoneOffset.UTC));

        scheduler.tick();

        verify(cleanupService, never()).cleanupExpiredSessions();
    }

    @Test
    void initShouldNotInvokeCleanupSynchronously() {
        SessionRetentionCleanupService cleanupService = mock(SessionRetentionCleanupService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isSessionRetentionEnabled()).thenReturn(true);
        when(runtimeConfigService.getSessionRetentionCleanupInterval()).thenReturn(Duration.ofHours(24));

        SessionRetentionScheduler scheduler = new SessionRetentionScheduler(
                cleanupService,
                runtimeConfigService,
                Clock.fixed(Instant.parse("2026-04-19T12:00:00Z"), ZoneOffset.UTC));

        try {
            scheduler.init();
            verify(cleanupService, never()).cleanupExpiredSessions();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void tickShouldRecoverAfterCleanupFailure() {
        SessionRetentionCleanupService cleanupService = mock(SessionRetentionCleanupService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isSessionRetentionEnabled()).thenReturn(true);
        when(runtimeConfigService.getSessionRetentionCleanupInterval()).thenReturn(Duration.ofHours(24));
        doThrow(new IllegalStateException("boom")).when(cleanupService).cleanupExpiredSessions();

        SessionRetentionScheduler scheduler = new SessionRetentionScheduler(
                cleanupService,
                runtimeConfigService,
                Clock.fixed(Instant.parse("2026-04-19T12:00:00Z"), ZoneOffset.UTC));

        scheduler.tick();
        scheduler.tick();

        verify(cleanupService, times(2)).cleanupExpiredSessions();
    }
}
