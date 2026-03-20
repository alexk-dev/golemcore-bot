package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.UpdateActionResult;
import me.golemcore.bot.domain.model.UpdateState;
import me.golemcore.bot.domain.model.UpdateStatus;
import me.golemcore.bot.domain.model.UpdateVersionInfo;
import me.golemcore.bot.domain.service.UpdateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateControllerTest {

    private UpdateService updateService;
    private UpdateController controller;

    @BeforeEach
    void setUp() {
        updateService = mock(UpdateService.class);
        controller = new UpdateController(updateService);
    }

    @Test
    void shouldReturnUpdateStatus() {
        UpdateStatus status = UpdateStatus.builder()
                .state(UpdateState.IDLE)
                .enabled(true)
                .current(UpdateVersionInfo.builder().version("0.4.0").source("image").build())
                .lastCheckAt(Instant.parse("2026-02-22T13:53:29Z"))
                .build();
        when(updateService.getStatus()).thenReturn(status);

        StepVerifier.create(controller.getStatus())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals(UpdateState.IDLE, response.getBody().getState());
                })
                .verifyComplete();
    }

    @Test
    void shouldCallCheckEndpoint() {
        UpdateActionResult checkResult = UpdateActionResult.builder()
                .success(true)
                .message("Update available: 0.4.2")
                .version("0.4.2")
                .build();
        when(updateService.check()).thenReturn(checkResult);

        StepVerifier.create(controller.check())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("Update available: 0.4.2", response.getBody().getMessage());
                })
                .verifyComplete();

        verify(updateService).check();
    }

    @Test
    void shouldCallUpdateNowEndpoint() {
        UpdateActionResult updateResult = UpdateActionResult.builder()
                .success(true)
                .message("Update 0.4.2 is being applied. JVM restart scheduled.")
                .version("0.4.2")
                .build();
        when(updateService.updateNow()).thenReturn(updateResult);

        StepVerifier.create(controller.updateNow())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("0.4.2", response.getBody().getVersion());
                })
                .verifyComplete();

        verify(updateService).updateNow();
    }

    @Test
    void shouldReturnUpdateConfig() {
        RuntimeConfig.UpdateConfig config = RuntimeConfig.UpdateConfig.builder()
                .autoEnabled(true)
                .checkIntervalMinutes(60)
                .maintenanceWindowEnabled(true)
                .maintenanceWindowStartUtc("01:00")
                .maintenanceWindowEndUtc("03:00")
                .build();
        when(updateService.getConfig()).thenReturn(config);

        StepVerifier.create(controller.getConfig())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertTrue(response.getBody().getMaintenanceWindowEnabled());
                })
                .verifyComplete();
    }

    @Test
    void shouldUpdateUpdateConfig() {
        RuntimeConfig.UpdateConfig request = RuntimeConfig.UpdateConfig.builder()
                .autoEnabled(false)
                .checkIntervalMinutes(180)
                .maintenanceWindowEnabled(false)
                .maintenanceWindowStartUtc("00:00")
                .maintenanceWindowEndUtc("00:00")
                .build();
        when(updateService.updateConfig(request)).thenReturn(request);

        StepVerifier.create(controller.updateConfig(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertFalse(response.getBody().getAutoEnabled());
                    assertEquals(180, response.getBody().getCheckIntervalMinutes());
                })
                .verifyComplete();

        verify(updateService).updateConfig(request);
    }
}
