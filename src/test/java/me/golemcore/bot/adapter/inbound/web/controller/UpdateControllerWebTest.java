package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.GlobalExceptionHandler;
import me.golemcore.bot.adapter.inbound.web.dto.UpdateConfirmRequest;
import me.golemcore.bot.domain.service.UpdateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpdateControllerWebTest {

    private UpdateService updateService;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        updateService = mock(UpdateService.class);
        UpdateController controller = new UpdateController(updateService);

        webTestClient = WebTestClient.bindToController(controller)
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldMapIllegalArgumentToBadRequestForApply() {
        when(updateService.apply("BAD123"))
                .thenThrow(new IllegalArgumentException("Invalid confirmation token"));

        webTestClient.post()
                .uri("/api/system/update/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateConfirmRequest("BAD123"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").isEqualTo("Invalid confirmation token");
    }

    @Test
    void shouldMapIllegalStateToConflictForPrepare() {
        when(updateService.prepare())
                .thenThrow(new IllegalStateException("No available update. Run check first."));

        webTestClient.post()
                .uri("/api/system/update/prepare")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.message").isEqualTo("No available update. Run check first.");
    }
}
