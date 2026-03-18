package me.golemcore.bot.adapter.inbound.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import me.golemcore.bot.domain.service.HiveConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

class HiveControllerTest {

    private HiveConnectionService hiveConnectionService;
    private HiveController controller;

    @BeforeEach
    void setUp() {
        hiveConnectionService = mock(HiveConnectionService.class);
        controller = new HiveController(hiveConnectionService);
    }

    @Test
    void shouldReturnHiveStatus() {
        when(hiveConnectionService.getStatus()).thenReturn(new HiveConnectionService.HiveStatusSnapshot(
                "CONNECTED",
                true,
                false,
                false,
                true,
                "https://hive.example.com",
                "Builder",
                "lab-a",
                true,
                "golem-1",
                null,
                30,
                Instant.parse("2026-03-18T00:00:00Z"),
                Instant.parse("2026-03-18T00:00:05Z"),
                null));

        StepVerifier.create(controller.getStatus())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("CONNECTED", response.getBody().state());
                })
                .verifyComplete();
    }

    @Test
    void shouldForwardJoinRequest() {
        HiveConnectionService.HiveStatusSnapshot snapshot = new HiveConnectionService.HiveStatusSnapshot(
                "CONNECTED",
                true,
                false,
                false,
                true,
                "https://hive.example.com",
                "Builder",
                "lab-a",
                true,
                "golem-1",
                null,
                30,
                Instant.parse("2026-03-18T00:00:00Z"),
                Instant.parse("2026-03-18T00:00:05Z"),
                null);
        when(hiveConnectionService.join("token:https://hive.example.com")).thenReturn(snapshot);

        StepVerifier.create(controller.join(new HiveController.JoinHiveRequest("token:https://hive.example.com")))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("golem-1", response.getBody().golemId());
                })
                .verifyComplete();

        verify(hiveConnectionService).join("token:https://hive.example.com");
    }
}
