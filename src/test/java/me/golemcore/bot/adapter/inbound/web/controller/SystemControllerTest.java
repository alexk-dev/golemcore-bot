package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.SystemHealthResponse;
import me.golemcore.bot.domain.component.BrowserComponent;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import reactor.test.StepVerifier;

class SystemControllerTest {

    private ChannelPort telegramPort;
    private ChannelPort webPort;
    private BotProperties botProperties;
    private RuntimeConfigService runtimeConfigService;
    private StoragePort storagePort;
    private SystemController controller;

    @BeforeEach
    void setUp() {
        telegramPort = mock(ChannelPort.class);
        when(telegramPort.getChannelType()).thenReturn("telegram");
        when(telegramPort.isRunning()).thenReturn(true);

        webPort = mock(ChannelPort.class);
        when(webPort.getChannelType()).thenReturn("web");
        when(webPort.isRunning()).thenReturn(false);

        botProperties = new BotProperties();
        runtimeConfigService = mock(RuntimeConfigService.class);
        storagePort = mock(StoragePort.class);
        when(storagePort.listObjects("sessions", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("telegram:1.json")));
        when(storagePort.listObjects("usage", ""))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(false);
        when(runtimeConfigService.isAutoModeEnabled()).thenReturn(false);
        BrowserComponent browserComponent = mock(BrowserComponent.class);
        controller = new SystemController(List.of(telegramPort, webPort), botProperties, runtimeConfigService,
                storagePort, browserComponent);
    }

    @Test
    void shouldReturnHealthStatus() {
        StepVerifier.create(controller.health())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SystemHealthResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("UP", body.getStatus());
                    assertTrue(body.getUptimeMs() >= 0);
                    assertEquals(2, body.getChannels().size());
                    assertTrue(body.getChannels().get("telegram").isRunning());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnConfig() {
        StepVerifier.create(controller.getConfig())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    Map<String, Object> body = response.getBody();
                    assertNotNull(body);
                    assertTrue(body.containsKey("llmProvider"));
                    assertTrue(body.containsKey("maxIterations"));
                    assertTrue(body.containsKey("dashboardEnabled"));
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnChannels() {
        StepVerifier.create(controller.getChannels())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<Map<String, Object>> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(2, body.size());
                    assertEquals("telegram", body.get(0).get("type"));
                    assertEquals(true, body.get(0).get("running"));
                    assertEquals("web", body.get(1).get("type"));
                    assertEquals(false, body.get(1).get("running"));
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnDiagnostics() {
        StepVerifier.create(controller.getDiagnostics())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    Map<String, Object> body = response.getBody();
                    assertNotNull(body);
                    assertTrue(body.containsKey("storage"));
                    assertTrue(body.containsKey("environment"));
                    assertTrue(body.containsKey("runtime"));
                })
                .verifyComplete();
    }
}
