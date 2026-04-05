package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeConfigServiceSelfEvolvingTest {

    private StoragePort storagePort;
    private RuntimeConfigService service;
    private ObjectMapper objectMapper;
    private Map<String, String> persistedSections;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        persistedSections = new ConcurrentHashMap<>();

        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    persistedSections.put(fileName, content);
                    return CompletableFuture.completedFuture(null);
                });
        when(storagePort.getText(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    return CompletableFuture.completedFuture(persistedSections.get(fileName));
                });

        service = new RuntimeConfigService(storagePort, new SelfEvolvingBootstrapOverrideService(new BotProperties()));
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldDefaultSelfEvolvingToDisabledApprovalGatedMode() {
        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getSelfEvolving());
        assertFalse(config.getSelfEvolving().getEnabled());
        assertNotNull(config.getSelfEvolving().getPromotion());
        assertEquals("approval_gate", config.getSelfEvolving().getPromotion().getMode());
    }

    @Test
    void shouldPersistSelfEvolvingSection() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        config.setSelfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(true)
                .judge(RuntimeConfig.SelfEvolvingJudgeConfig.builder()
                        .enabled(true)
                        .primaryTier("smart")
                        .tiebreakerTier("deep")
                        .evolutionTier("coding")
                        .requireEvidenceAnchors(true)
                        .build())
                .promotion(RuntimeConfig.SelfEvolvingPromotionConfig.builder()
                        .mode("shadow_then_approval")
                        .allowAutoAccept(true)
                        .shadowRequired(true)
                        .canaryRequired(true)
                        .hiveApprovalPreferred(true)
                        .build())
                .build());

        service.updateRuntimeConfig(config);

        assertTrue(persistedSections.containsKey("self-evolving.json"));
        Map<?, ?> persistedSelfEvolving = objectMapper.readValue(persistedSections.get("self-evolving.json"),
                Map.class);
        assertEquals(true, persistedSelfEvolving.get("enabled"));
        assertEquals("smart", ((Map<?, ?>) persistedSelfEvolving.get("judge")).get("primaryTier"));
        assertEquals("shadow_then_approval", ((Map<?, ?>) persistedSelfEvolving.get("promotion")).get("mode"));
    }

    @Test
    void shouldExposeDefaultSelfEvolvingGettersWhenSectionIsMissing() {
        assertFalse(service.isSelfEvolvingEnabled());
        assertFalse(service.isSelfEvolvingTracePayloadOverrideEnabled());
        assertEquals("smart", service.getSelfEvolvingJudgePrimaryTier());
        assertEquals("deep", service.getSelfEvolvingJudgeTiebreakerTier());
        assertEquals("deep", service.getSelfEvolvingJudgeEvolutionTier());
        assertEquals("approval_gate", service.getSelfEvolvingPromotionMode());
    }

    @Test
    void shouldNormalizeLegacySelfEvolvingJudgeTiersToSupportedModelTiers() {
        RuntimeConfig config = service.getRuntimeConfig();
        config.setSelfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(true)
                .judge(RuntimeConfig.SelfEvolvingJudgeConfig.builder()
                        .enabled(true)
                        .primaryTier("standard")
                        .tiebreakerTier("premium")
                        .evolutionTier("premium")
                        .requireEvidenceAnchors(true)
                        .uncertaintyThreshold(0.22d)
                        .build())
                .build());

        service.updateRuntimeConfig(config);

        RuntimeConfig normalized = service.getRuntimeConfig();
        assertEquals("smart", normalized.getSelfEvolving().getJudge().getPrimaryTier());
        assertEquals("deep", normalized.getSelfEvolving().getJudge().getTiebreakerTier());
        assertEquals("deep", normalized.getSelfEvolving().getJudge().getEvolutionTier());
    }

    @Test
    void shouldNormalizeInvalidSelfEvolvingValuesBackToDefaults() {
        RuntimeConfig config = service.getRuntimeConfig();
        config.setSelfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(true)
                .tracePayloadOverride(false)
                .capture(RuntimeConfig.SelfEvolvingCaptureConfig.builder()
                        .llm("invalid")
                        .tool("meta_only")
                        .context(" ")
                        .skill(null)
                        .tier("full")
                        .infra("invalid")
                        .build())
                .judge(RuntimeConfig.SelfEvolvingJudgeConfig.builder()
                        .enabled(null)
                        .primaryTier(" ")
                        .tiebreakerTier(" deep ")
                        .evolutionTier(null)
                        .requireEvidenceAnchors(null)
                        .uncertaintyThreshold(2.0d)
                        .build())
                .evolution(RuntimeConfig.SelfEvolvingEvolutionConfig.builder()
                        .enabled(null)
                        .modes(List.of(" fix ", "fix", "derive", " "))
                        .artifactTypes(List.of(" skill ", "", "tool_policy", "skill"))
                        .build())
                .promotion(RuntimeConfig.SelfEvolvingPromotionConfig.builder()
                        .mode(" ")
                        .allowAutoAccept(null)
                        .shadowRequired(null)
                        .canaryRequired(null)
                        .hiveApprovalPreferred(null)
                        .build())
                .benchmark(RuntimeConfig.SelfEvolvingBenchmarkConfig.builder()
                        .enabled(null)
                        .harvestProductionRuns(null)
                        .autoCreateRegressionCases(null)
                        .build())
                .hive(RuntimeConfig.SelfEvolvingHiveConfig.builder()
                        .publishInspectionProjection(null)
                        .readonlyInspection(null)
                        .build())
                .build());

        service.updateRuntimeConfig(config);

        RuntimeConfig normalized = service.getRuntimeConfig();
        assertTrue(normalized.getSelfEvolving().getTracePayloadOverride());
        assertEquals("full", normalized.getSelfEvolving().getCapture().getLlm());
        assertEquals("meta_only", normalized.getSelfEvolving().getCapture().getTool());
        assertEquals("full", normalized.getSelfEvolving().getCapture().getContext());
        assertEquals("full", normalized.getSelfEvolving().getCapture().getSkill());
        assertEquals("full", normalized.getSelfEvolving().getCapture().getTier());
        assertEquals("meta_only", normalized.getSelfEvolving().getCapture().getInfra());
        assertTrue(normalized.getSelfEvolving().getJudge().getEnabled());
        assertEquals("smart", normalized.getSelfEvolving().getJudge().getPrimaryTier());
        assertEquals("deep", normalized.getSelfEvolving().getJudge().getTiebreakerTier());
        assertEquals("deep", normalized.getSelfEvolving().getJudge().getEvolutionTier());
        assertEquals(0.22d, normalized.getSelfEvolving().getJudge().getUncertaintyThreshold());
        assertEquals(List.of("fix", "derive"), normalized.getSelfEvolving().getEvolution().getModes());
        assertEquals(List.of("skill", "tool_policy"), normalized.getSelfEvolving().getEvolution().getArtifactTypes());
        assertEquals("approval_gate", normalized.getSelfEvolving().getPromotion().getMode());
        assertTrue(normalized.getSelfEvolving().getPromotion().getAllowAutoAccept());
        assertFalse(normalized.getSelfEvolving().getPromotion().getShadowRequired());
        assertFalse(normalized.getSelfEvolving().getPromotion().getCanaryRequired());
        assertTrue(normalized.getSelfEvolving().getPromotion().getHiveApprovalPreferred());
        assertTrue(normalized.getSelfEvolving().getBenchmark().getEnabled());
        assertTrue(normalized.getSelfEvolving().getBenchmark().getHarvestProductionRuns());
        assertTrue(normalized.getSelfEvolving().getBenchmark().getAutoCreateRegressionCases());
        assertTrue(normalized.getSelfEvolving().getHive().getPublishInspectionProjection());
        assertTrue(normalized.getSelfEvolving().getHive().getReadonlyInspection());
        assertEquals("hybrid", normalized.getSelfEvolving().getTactics().getSearch().getMode());
        assertTrue(normalized.getSelfEvolving().getTactics().getSearch().getEmbeddings().getAutoFallbackToBm25());
    }

    @Test
    void shouldNormalizeMissingLocalEmbeddingProviderToOllama() {
        RuntimeConfig config = service.getRuntimeConfig();
        config.setSelfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(true)
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                        .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                .mode("hybrid")
                                .embeddings(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig.builder()
                                        .provider(null)
                                        .baseUrl(null)
                                        .model("bge-m3")
                                        .build())
                                .build())
                        .build())
                .build());

        service.updateRuntimeConfig(config);

        RuntimeConfig normalized = service.getRuntimeConfig();
        assertEquals("ollama", normalized.getSelfEvolving().getTactics().getSearch().getEmbeddings().getProvider());
        assertEquals("bge-m3", normalized.getSelfEvolving().getTactics().getSearch().getEmbeddings().getModel());
        assertTrue(normalized.getSelfEvolving().getTactics().getSearch().getEmbeddings().getEnabled());
    }
}
