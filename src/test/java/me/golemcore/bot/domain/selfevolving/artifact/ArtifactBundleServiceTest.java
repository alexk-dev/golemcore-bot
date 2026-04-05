package me.golemcore.bot.domain.selfevolving.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.adapter.outbound.selfevolving.JsonArtifactRepositoryAdapter;

class ArtifactBundleServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-03-31T20:00:00Z");

    private StoragePort storagePort;
    private RuntimeConfigService runtimeConfigService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(eq("self-evolving"), eq("artifact-bundles.json"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigService.isSelfEvolvingTracePayloadOverrideEnabled()).thenReturn(true);
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("approval_gate");
        when(runtimeConfigService.getSelfEvolvingJudgePrimaryTier()).thenReturn("smart");
        when(runtimeConfigService.getSelfEvolvingJudgeTiebreakerTier()).thenReturn("deep");
        when(runtimeConfigService.getSelfEvolvingJudgeEvolutionTier()).thenReturn("deep");
    }

    @Test
    void shouldSnapshotSkillAndPolicyBindingsForActiveContext() {
        ArtifactBundleService service = new ArtifactBundleService(new JsonArtifactRepositoryAdapter(storagePort),
                runtimeConfigService,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-1").chatId("chat-1").metadata(Map.of()).build())
                .activeSkill(Skill.builder().name("planner").build())
                .activeSkills(List.of(
                        Skill.builder().name("planner").build(),
                        Skill.builder().name("reviewer").build(),
                        Skill.builder().build()))
                .modelTier("balanced")
                .build();
        context.setAttribute(ContextAttributes.HIVE_GOLEM_ID, "golem-1");
        context.setAttribute(ContextAttributes.CONVERSATION_KEY, "conv-1");

        ArtifactBundleRecord bundle = service.snapshot(context);

        assertNotNull(bundle.getId());
        assertEquals("golem-1", bundle.getGolemId());
        assertEquals("SNAPSHOT", bundle.getStatus());
        assertEquals(FIXED_INSTANT, bundle.getCreatedAt());
        assertEquals(List.of("planner", "reviewer"), bundle.getSkillVersions());
        assertEquals("skill", bundle.getArtifactSubtypeBindings().get("skill:planner"));
        assertEquals("routing_policy", bundle.getArtifactTypeBindings().get("routing_policy:tier"));
        assertEquals("balanced", bundle.getTierBindings().get("active"));
        assertEquals("smart", bundle.getTierBindings().get("judge.primary"));
        assertEquals("deep", bundle.getTierBindings().get("judge.tiebreaker"));
        assertEquals("approval_gate", bundle.getConfigSnapshot().get("promotionMode"));
        assertEquals("conv-1", bundle.getConfigSnapshot().get("conversationKey"));
        assertTrue(bundle.getArtifactKeyBindings().containsKey("governance_policy:approval"));
        assertFalse(service.getBundles().isEmpty());
    }

    @Test
    void shouldRefreshExistingBundleAndPreservePromotionMetadata() throws Exception {
        ArtifactBundleRecord existing = ArtifactBundleRecord.builder()
                .id("bundle-1")
                .golemId("golem-1")
                .status("ACTIVE")
                .createdAt(Instant.parse("2026-03-30T12:00:00Z"))
                .activatedAt(Instant.parse("2026-03-30T13:00:00Z"))
                .sourceCandidateId("candidate-1")
                .sourceRunId("run-1")
                .artifactRevisionBindings(Map.of("stream-1", "rev-1"))
                .artifactKeyBindings(Map.of("skill:legacy", "skill:legacy"))
                .build();
        when(storagePort.getText("self-evolving", "artifact-bundles.json"))
                .thenReturn(CompletableFuture.completedFuture(objectMapper.writeValueAsString(List.of(existing))));

        ArtifactBundleService service = new ArtifactBundleService(new JsonArtifactRepositoryAdapter(storagePort),
                runtimeConfigService,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-1")
                        .metadata(Map.of(ContextAttributes.HIVE_GOLEM_ID, "meta-golem")).build())
                .activeSkills(List.of())
                .build();

        ArtifactBundleRecord refreshed = service.refresh("bundle-1", context);

        assertEquals("bundle-1", refreshed.getId());
        assertEquals("meta-golem", refreshed.getGolemId());
        assertEquals("ACTIVE", refreshed.getStatus());
        assertEquals(existing.getCreatedAt(), refreshed.getCreatedAt());
        assertEquals(existing.getActivatedAt(), refreshed.getActivatedAt());
        assertEquals("candidate-1", refreshed.getSourceCandidateId());
        assertEquals("run-1", refreshed.getSourceRunId());
        assertEquals("rev-1", refreshed.getArtifactRevisionBindings().get("stream-1"));
        assertTrue(refreshed.getArtifactKeyBindings().containsKey("skill:default"));

        List<ArtifactBundleRecord> persisted = service.getBundles();
        assertEquals(1, persisted.size());
        assertEquals("bundle-1", persisted.getFirst().getId());
    }

    @Test
    void shouldFallbackToSnapshotAndLocalSessionGolemWhenBundleIdIsBlank() {
        ArtifactBundleService service = new ArtifactBundleService(new JsonArtifactRepositoryAdapter(storagePort),
                runtimeConfigService,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-99").metadata(Map.of()).build())
                .activeSkills(List.of())
                .build();

        ArtifactBundleRecord refreshed = service.refresh(" ", context);

        assertNotNull(refreshed.getId());
        assertEquals("local-session-99", refreshed.getGolemId());
        assertEquals("SNAPSHOT", refreshed.getStatus());
        assertEquals("skill:default", refreshed.getArtifactKeyBindings().get("skill:default"));
    }

    @Test
    void shouldBindBaseRevisionsForCandidateStreams() {
        ArtifactBundleRecord existing = ArtifactBundleRecord.builder()
                .id("bundle-1")
                .artifactRevisionBindings(Map.of())
                .build();
        ArtifactBundleService service = new ArtifactBundleService(new JsonArtifactRepositoryAdapter(storagePort),
                runtimeConfigService,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        service.save(existing);

        service.bindBaseRevisions("bundle-1", List.of(
                EvolutionCandidate.builder()
                        .id("candidate-1")
                        .baseVersion("bundle-1")
                        .artifactStreamId("stream-1")
                        .baseContentRevisionId("rev-1")
                        .build(),
                EvolutionCandidate.builder()
                        .id("candidate-2")
                        .baseVersion("bundle-1")
                        .artifactStreamId("stream-2")
                        .baseContentRevisionId("rev-2")
                        .build(),
                EvolutionCandidate.builder()
                        .id("candidate-3")
                        .baseVersion("bundle-2")
                        .artifactStreamId("stream-3")
                        .baseContentRevisionId("rev-3")
                        .build()));

        ArtifactBundleRecord updated = service.getBundles().getFirst();
        assertEquals("rev-1", updated.getArtifactRevisionBindings().get("stream-1"));
        assertEquals("rev-2", updated.getArtifactRevisionBindings().get("stream-2"));
        assertFalse(updated.getArtifactRevisionBindings().containsKey("stream-3"));
    }

    @Test
    void shouldIgnoreBrokenStoragePayloads() {
        when(storagePort.getText("self-evolving", "artifact-bundles.json"))
                .thenReturn(CompletableFuture.completedFuture("not-json"));
        ArtifactBundleService service = new ArtifactBundleService(new JsonArtifactRepositoryAdapter(storagePort),
                runtimeConfigService,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

        List<ArtifactBundleRecord> bundles = service.getBundles();

        assertTrue(bundles.isEmpty());
    }
}
