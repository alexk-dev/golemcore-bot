package me.golemcore.bot.adapter.inbound.runtime;

import me.golemcore.bot.domain.service.UpdateRuntimeCleanupService;
import me.golemcore.bot.port.outbound.UpdateVersionPort;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateRuntimeCleanupLifecycleTest {

    @Test
    void shouldForwardRunningVersionToCleanupService() {
        UpdateRuntimeCleanupService cleanupService = mock(UpdateRuntimeCleanupService.class);
        UpdateVersionPort updateVersionPort = mock(UpdateVersionPort.class);
        when(updateVersionPort.currentVersion()).thenReturn("0.4.2");

        UpdateRuntimeCleanupLifecycle lifecycle = new UpdateRuntimeCleanupLifecycle(cleanupService, updateVersionPort);

        lifecycle.onApplicationReady();

        verify(updateVersionPort).currentVersion();
        verify(cleanupService).cleanupAfterSuccessfulStartup("0.4.2");
    }
}
