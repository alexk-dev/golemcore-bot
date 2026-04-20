package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.client.dto.LogEntryDto;
import me.golemcore.bot.client.dto.SystemHealthResponse;
import me.golemcore.bot.adapter.inbound.web.logstream.DashboardLogService;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.selfevolving.tactic.LocalEmbeddingBootstrapService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.channel.ChannelPort;
import me.golemcore.bot.port.outbound.RagPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemControllerTest {

    private ChannelPort telegramPort;
    private ChannelPort webPort;
    private BotProperties botProperties;
    private RuntimeConfigService runtimeConfigService;
    private StoragePort storagePort;
    private RagPort ragPort;
    private DashboardLogService dashboardLogService;
    private LocalEmbeddingBootstrapService localEmbeddingBootstrapService;
    private ObjectProvider<BuildProperties> buildPropertiesProvider;
    private ObjectProvider<GitProperties> gitPropertiesProvider;
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
        ragPort = mock(RagPort.class);
        dashboardLogService = mock(DashboardLogService.class);
        localEmbeddingBootstrapService = mock(LocalEmbeddingBootstrapService.class);
        buildPropertiesProvider = mockObjectProvider();
        gitPropertiesProvider = mockObjectProvider();

        when(storagePort.listObjects("sessions", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("telegram:1.json")));
        when(storagePort.listObjects("usage", ""))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));

        when(runtimeConfigService.isVoiceEnabled()).thenReturn(false);
        when(runtimeConfigService.isPlanEnabled()).thenReturn(false);
        when(runtimeConfigService.isAutoModeEnabled()).thenReturn(false);
        when(ragPort.isAvailable()).thenReturn(false);

        controller = new SystemController(
                new ChannelRegistry(List.of(telegramPort, webPort)),
                botProperties,
                runtimeConfigService,
                storagePort,
                ragPort,
                buildPropertiesProvider,
                gitPropertiesProvider,
                dashboardLogService,
                localEmbeddingBootstrapService);
    }

    @Test
    void shouldReturnHealthStatus() {
        when(localEmbeddingBootstrapService.probeStatus()).thenReturn(TacticSearchStatus.builder()
                .mode("bm25")
                .degraded(false)
                .build());
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
    void shouldKeepHealthUpWhileSurfacingSelfEvolvingEmbeddingDegradation() {
        when(localEmbeddingBootstrapService.probeStatus()).thenReturn(TacticSearchStatus.builder()
                .mode("bm25")
                .reason("Ollama did not become healthy within 5 seconds")
                .degraded(true)
                .runtimeState("degraded_start_timeout")
                .owned(true)
                .restartAttempts(1)
                .runtimeHealthy(false)
                .build());

        StepVerifier.create(controller.health())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SystemHealthResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("UP", body.getStatus());
                    assertNotNull(body.getSelfEvolvingEmbeddings());
                    assertTrue(body.getSelfEvolvingEmbeddings().isDegraded());
                    assertEquals("degraded_start_timeout", body.getSelfEvolvingEmbeddings().getRuntimeState());
                    assertTrue(body.getSelfEvolvingEmbeddings().isOwned());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnDevVersionWhenBuildPropertiesAbsent() {
        StepVerifier.create(controller.health())
                .assertNext(response -> {
                    SystemHealthResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("dev", body.getVersion());
                    assertNull(body.getGitCommit());
                    assertNull(body.getBuildTime());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnVersionFromBuildProperties() {
        Properties props = new Properties();
        props.setProperty("version", "1.2.3");
        props.setProperty("time", "2026-02-19T12:00:00Z");
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(new BuildProperties(props));

        StepVerifier.create(controller.health())
                .assertNext(response -> {
                    SystemHealthResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("1.2.3", body.getVersion());
                    assertNotNull(body.getBuildTime());
                    assertTrue(body.getBuildTime().contains("2026-02-19"));
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnGitCommitFromGitProperties() {
        Properties gitProps = new Properties();
        gitProps.setProperty("commit.id.abbrev", "abc1234");
        when(gitPropertiesProvider.getIfAvailable()).thenReturn(new GitProperties(gitProps));

        StepVerifier.create(controller.health())
                .assertNext(response -> {
                    SystemHealthResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("abc1234", body.getGitCommit());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnAllVersionFieldsWhenBothProvidersAvailable() {
        Properties buildProps = new Properties();
        buildProps.setProperty("version", "0.3.0");
        buildProps.setProperty("time", "2026-02-19T15:00:00Z");
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(new BuildProperties(buildProps));

        Properties gitProps = new Properties();
        gitProps.setProperty("commit.id.abbrev", "deadbee");
        when(gitPropertiesProvider.getIfAvailable()).thenReturn(new GitProperties(gitProps));

        StepVerifier.create(controller.health())
                .assertNext(response -> {
                    SystemHealthResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("0.3.0", body.getVersion());
                    assertEquals("deadbee", body.getGitCommit());
                    assertNotNull(body.getBuildTime());
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleNullBuildTimeGracefully() {
        Properties props = new Properties();
        props.setProperty("version", "0.1.0");
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(new BuildProperties(props));

        StepVerifier.create(controller.health())
                .assertNext(response -> {
                    SystemHealthResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("0.1.0", body.getVersion());
                    assertNull(body.getBuildTime());
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
                    assertFalse(body.containsKey("planEnabled"));
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

    @Test
    void shouldReturnLogsPage() {
        List<LogEntryDto> items = List.of(
                LogEntryDto.builder()
                        .seq(1L)
                        .timestamp("2026-01-01T00:00:00Z")
                        .level("INFO")
                        .logger("me.golemcore.bot.Test")
                        .thread("main")
                        .message("hello")
                        .build());
        when(dashboardLogService.getLogsPage(null, null))
                .thenReturn(new DashboardLogService.LogsSlice(items, 1L, 1L, false));

        StepVerifier.create(controller.getLogs(null, null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals(1, response.getBody().getItems().size());
                    assertEquals(1L, response.getBody().getOldestSeq());
                    assertEquals(1L, response.getBody().getNewestSeq());
                })
                .verifyComplete();
    }

    @Test
    void shouldForwardLogsPagingParametersToService() {
        when(dashboardLogService.getLogsPage(10L, 25))
                .thenReturn(new DashboardLogService.LogsSlice(List.of(), null, null, false));

        StepVerifier.create(controller.getLogs(10L, 25))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(dashboardLogService).getLogsPage(10L, 25);
    }

    @Test
    void shouldReturnNegativeFileCountsWhenStorageFails() {
        when(storagePort.listObjects("sessions", ""))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("storage down")));
        when(storagePort.listObjects("usage", ""))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("storage down")));

        StepVerifier.create(controller.getDiagnostics())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    Map<String, Object> storage = requireObjectMap(response.getBody(), "storage");
                    assertNotNull(storage);
                    assertEquals(-1, storage.get("sessionsFiles"));
                    assertEquals(-1, storage.get("usageFiles"));
                })
                .verifyComplete();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockObjectProvider() {
        return mock(ObjectProvider.class);
    }

    private static Map<String, Object> requireObjectMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertTrue(value instanceof Map<?, ?>);
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((entryKey, entryValue) -> result.put(String.valueOf(entryKey), entryValue));
        return result;
    }
}
