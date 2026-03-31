package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelfEvolvingRunServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-03-31T14:00:00Z");

    private StoragePort storagePort;
    private ArtifactBundleService artifactBundleService;
    private Clock clock;
    private SelfEvolvingRunService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        artifactBundleService = mock(ArtifactBundleService.class);
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        service = new SelfEvolvingRunService(storagePort, artifactBundleService, clock);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(eq("self-evolving"), eq("runs.json"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void shouldCreateRunRecordWithArtifactBundleBeforeJudging() throws Exception {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-1").chatId("chat-1").build())
                .build();
        context.setAttribute(ContextAttributes.HIVE_GOLEM_ID, "golem-1");
        when(artifactBundleService.snapshot(context)).thenReturn(ArtifactBundleRecord.builder()
                .id("bundle-1")
                .golemId("golem-1")
                .build());

        RunRecord run = service.startRun(context);

        assertNotNull(run.getId());
        assertEquals("golem-1", run.getGolemId());
        assertEquals("session-1", run.getSessionId());
        assertEquals("bundle-1", run.getArtifactBundleId());
        assertEquals("RUNNING", run.getStatus());
        assertEquals(FIXED_INSTANT, run.getStartedAt());
        verify(storagePort).putText(eq("self-evolving"), eq("runs.json"), anyString());

        String persistedJson = service.exportRunsJson();
        assertTrue(persistedJson.contains("\"artifactBundleId\":\"bundle-1\""));
    }
}
