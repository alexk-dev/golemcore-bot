package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.UpdateConfirmRequest;
import me.golemcore.bot.adapter.inbound.web.dto.UpdateRollbackIntentRequest;
import me.golemcore.bot.adapter.inbound.web.dto.UpdateRollbackRequest;
import me.golemcore.bot.domain.model.UpdateActionResult;
import me.golemcore.bot.domain.model.UpdateHistoryItem;
import me.golemcore.bot.domain.model.UpdateIntent;
import me.golemcore.bot.domain.model.UpdateState;
import me.golemcore.bot.domain.model.UpdateStatus;
import me.golemcore.bot.domain.model.UpdateVersionInfo;
import me.golemcore.bot.domain.service.UpdateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                .current(UpdateVersionInfo.builder().version("0.3.0").source("image").build())
                .lastCheckAt(Instant.parse("2026-02-19T12:00:00Z"))
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
    void shouldCreateApplyIntent() {
        UpdateIntent intent = UpdateIntent.builder()
                .operation("apply")
                .targetVersion("0.3.1")
                .confirmToken("ABC123")
                .expiresAt(Instant.parse("2026-02-19T12:02:00Z"))
                .build();
        when(updateService.createApplyIntent()).thenReturn(intent);

        StepVerifier.create(controller.createApplyIntent())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("apply", response.getBody().getOperation());
                })
                .verifyComplete();
    }

    @Test
    void shouldCallApplyAndRollbackEndpoints() {
        UpdateActionResult applyResult = UpdateActionResult.builder()
                .success(true)
                .message("apply")
                .version("0.3.1")
                .build();
        when(updateService.apply("ABC123")).thenReturn(applyResult);

        StepVerifier.create(controller.apply(new UpdateConfirmRequest("ABC123")))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("0.3.1", response.getBody().getVersion());
                })
                .verifyComplete();

        UpdateIntent rollbackIntent = UpdateIntent.builder()
                .operation("rollback")
                .targetVersion("0.3.0")
                .confirmToken("XYZ789")
                .expiresAt(Instant.parse("2026-02-19T12:02:00Z"))
                .build();
        when(updateService.createRollbackIntent("0.3.0")).thenReturn(rollbackIntent);

        StepVerifier.create(controller.createRollbackIntent(new UpdateRollbackIntentRequest("0.3.0")))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("rollback", response.getBody().getOperation());
                })
                .verifyComplete();

        UpdateActionResult rollbackResult = UpdateActionResult.builder()
                .success(true)
                .message("rollback")
                .version("0.3.0")
                .build();
        when(updateService.rollback("XYZ789", "0.3.0")).thenReturn(rollbackResult);

        StepVerifier.create(controller.rollback(new UpdateRollbackRequest("XYZ789", "0.3.0")))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("0.3.0", response.getBody().getVersion());
                })
                .verifyComplete();

        verify(updateService).apply("ABC123");
        verify(updateService).createRollbackIntent("0.3.0");
        verify(updateService).rollback("XYZ789", "0.3.0");
    }

    @Test
    void shouldReturnHistory() {
        List<UpdateHistoryItem> history = List.of(
                UpdateHistoryItem.builder()
                        .operation("check")
                        .version("0.3.1")
                        .timestamp(Instant.parse("2026-02-19T12:00:00Z"))
                        .result("SUCCESS")
                        .message("available")
                        .build());
        when(updateService.getHistory()).thenReturn(history);

        StepVerifier.create(controller.history())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals(1, response.getBody().size());
                })
                .verifyComplete();
    }
}
