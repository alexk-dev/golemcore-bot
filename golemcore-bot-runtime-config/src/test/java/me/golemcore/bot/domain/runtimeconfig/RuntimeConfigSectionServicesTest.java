package me.golemcore.bot.domain.runtimeconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.domain.model.RuntimeConfig;
import org.junit.jupiter.api.Test;

class RuntimeConfigSectionServicesTest {

    @Test
    void shouldCreateMissingSectionsAndApplyDefaultValues() {
        RuntimeConfig cfg = RuntimeConfig.builder().build();
        cfg.setTelegram(null);
        cfg.setModelRouter(null);
        cfg.setLlm(null);
        cfg.setModelRegistry(null);
        cfg.setTools(null);
        cfg.setMcp(null);
        cfg.setVoice(null);
        cfg.setRateLimit(null);
        cfg.setSecurity(null);
        cfg.setAutoMode(null);
        cfg.setPlan(null);
        cfg.setSkills(null);
        cfg.setUpdate(null);
        cfg.setUsage(null);
        cfg.setTelemetry(null);
        cfg.setTracing(null);
        cfg.setCompaction(null);
        cfg.setTurn(null);
        cfg.setToolLoop(null);
        cfg.setSessionRetention(null);
        cfg.setDelayedActions(null);
        cfg.setMemory(null);
        cfg.setResilience(null);
        cfg.setHive(null);
        cfg.setSelfEvolving(null);

        allSectionServices().forEach(service -> service.normalize(cfg));

        assertEquals("invite_only", cfg.getTelegram().getAuthMode());
        assertNotNull(cfg.getModelRouter().getRouting());
        assertNotNull(cfg.getLlm().getProviders());
        assertEquals(RuntimeConfigDefaults.DEFAULT_MODEL_REGISTRY_BRANCH, cfg.getModelRegistry().getBranch());
        assertNotNull(cfg.getTools().getShellEnvironmentVariables());
        assertNotNull(cfg.getMcp());
        assertNotNull(cfg.getVoice());
        assertNotNull(cfg.getRateLimit());
        assertNotNull(cfg.getSecurity());
        assertEquals(RuntimeConfigDefaults.DEFAULT_AUTO_REFLECTION_ENABLED, cfg.getAutoMode().getReflectionEnabled());
        assertNotNull(cfg.getSkills());
        assertEquals(RuntimeConfigDefaults.DEFAULT_UPDATE_CHECK_INTERVAL_MINUTES,
                cfg.getUpdate().getCheckIntervalMinutes());
        assertNotNull(cfg.getUsage());
        assertNotNull(cfg.getTelemetry());
        assertEquals(RuntimeConfigDefaults.DEFAULT_TRACING_SESSION_TRACE_BUDGET_MB,
                cfg.getTracing().getSessionTraceBudgetMb());
        assertEquals(RuntimeConfigDefaults.DEFAULT_TURN_MAX_SKILL_TRANSITIONS, cfg.getTurn().getMaxSkillTransitions());
        assertEquals(RuntimeConfigDefaults.DEFAULT_TOOL_LOOP_MAX_LLM_CALLS, cfg.getToolLoop().getMaxLlmCalls());
        assertEquals(RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_MAX_PENDING_PER_SESSION,
                cfg.getDelayedActions().getMaxPendingPerSession());
        assertEquals(RuntimeConfigDefaults.DEFAULT_MEMORY_VERSION, cfg.getMemory().getVersion());
        assertNotNull(cfg.getResilience().getFollowThrough());
        assertEquals(RuntimeConfigDefaults.DEFAULT_HIVE_ENABLED, cfg.getHive().getEnabled());
        assertNotNull(cfg.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal());
    }

    @Test
    void shouldNormalizeAlreadyInitializedSectionsWithoutReplacingThem() {
        RuntimeConfig cfg = RuntimeConfig.builder().build();

        allSectionServices().forEach(service -> service.normalize(cfg));

        assertNotNull(cfg.getTelegram());
        assertNotNull(cfg.getModelRouter().getRouting());
        assertNotNull(cfg.getLlm().getProviders());
        assertEquals(RuntimeConfigDefaults.DEFAULT_MODEL_REGISTRY_BRANCH, cfg.getModelRegistry().getBranch());
        assertTrue(cfg.getModelRouter().getDynamicTierEnabled());
        assertEquals(RuntimeConfigDefaults.DEFAULT_TRACING_MAX_TRACES_PER_SESSION,
                cfg.getTracing().getMaxTracesPerSession());
        assertEquals(RuntimeConfigDefaults.DEFAULT_MEMORY_VERSION, cfg.getMemory().getVersion());
        assertEquals(RuntimeConfigDefaults.DEFAULT_HIVE_AUTO_CONNECT, cfg.getHive().getAutoConnect());
        assertEquals(RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TRACE_PAYLOAD_OVERRIDE,
                cfg.getSelfEvolving().getTracePayloadOverride());
    }

    @Test
    void shouldNormalizeInvalidRuntimeBounds() {
        RuntimeConfig cfg = RuntimeConfig.builder().build();
        cfg.setCompaction(
                RuntimeConfig.CompactionConfig.builder().modelThresholdRatio(2.0d).preserveTurnBoundaries(null)
                        .detailsEnabled(null).detailsMaxItemsPerCategory(null).summaryTimeoutMs(null).build());
        cfg.setTurn(RuntimeConfig.TurnConfig.builder().maxSkillTransitions(0).queueSteeringMode(" ")
                .queueFollowUpMode("invalid").progressBatchSize(0).progressMaxSilenceSeconds(0)
                .progressSummaryTimeoutMs(999).build());
        cfg.setToolLoop(RuntimeConfig.ToolLoopConfig.builder().maxLlmCalls(0).maxToolExecutions(0).build());
        cfg.setSessionRetention(RuntimeConfig.SessionRetentionConfig.builder().maxAge("bad").cleanupInterval("also-bad")
                .protectActiveSessions(null).protectSessionsWithPlans(null).protectSessionsWithDelayedActions(null)
                .build());
        cfg.setDelayedActions(RuntimeConfig.DelayedActionsConfig.builder().tickSeconds(0).maxPendingPerSession(999)
                .maxDelay("bad").defaultMaxAttempts(0).leaseDuration("bad").retentionAfterCompletion("bad")
                .allowRunLater(null).build());
        cfg.setTracing(RuntimeConfig.TracingConfig.builder().sessionTraceBudgetMb(0).maxSnapshotSizeKb(0)
                .maxSnapshotsPerSpan(0).maxTracesPerSession(0).resiliencePayloadSampleRate(Double.NaN).build());

        new SessionRuntimeConfigService().normalize(cfg);
        new DelayedActionsConfigService().normalize(cfg);
        new ObservabilityConfigService().normalize(cfg);

        assertEquals(RuntimeConfigDefaults.DEFAULT_COMPACTION_MODEL_THRESHOLD_RATIO,
                cfg.getCompaction().getModelThresholdRatio());
        assertEquals(RuntimeConfigDefaults.DEFAULT_TURN_QUEUE_STEERING_MODE, cfg.getTurn().getQueueSteeringMode());
        assertEquals(RuntimeConfigDefaults.DEFAULT_TURN_QUEUE_FOLLOW_UP_MODE, cfg.getTurn().getQueueFollowUpMode());
        assertEquals(RuntimeConfigDefaults.DEFAULT_TOOL_LOOP_MAX_TOOL_EXECUTIONS,
                cfg.getToolLoop().getMaxToolExecutions());
        assertEquals(RuntimeConfigDefaults.DEFAULT_SESSION_RETENTION_MAX_AGE.toString(),
                cfg.getSessionRetention().getMaxAge());
        assertEquals(RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_MAX_DELAY.toString(),
                cfg.getDelayedActions().getMaxDelay());
        assertEquals(RuntimeConfigDefaults.DEFAULT_TRACING_RESILIENCE_PAYLOAD_SAMPLE_RATE,
                cfg.getTracing().getResiliencePayloadSampleRate());
    }

    @Test
    void shouldNormalizeModelFallbacksToolsMemoryAndSelfEvolvingEdges() {
        RuntimeConfig cfg = RuntimeConfig.builder().build();
        RuntimeConfig.TierBinding routing = RuntimeConfig.TierBinding.builder().model("openai/gpt-test").reasoning(" ")
                .temperature(3.0d).fallbacks(fallbacks()).build();
        cfg.setModelRouter(RuntimeConfig.ModelRouterConfig.builder().routing(routing).dynamicTierEnabled(null).build());
        cfg.setModelRegistry(RuntimeConfig.ModelRegistryConfig.builder().branch(" feature ").build());
        List<RuntimeConfig.ShellEnvironmentVariable> variables = new ArrayList<>();
        variables.add(null);
        variables.add(RuntimeConfig.ShellEnvironmentVariable.builder().name(" ").value("ignored").build());
        variables.add(RuntimeConfig.ShellEnvironmentVariable.builder().name(" API_TOKEN ").value(null).build());
        variables.add(RuntimeConfig.ShellEnvironmentVariable.builder().name("API_TOKEN").value("override").build());
        cfg.setTools(RuntimeConfig.ToolsConfig.builder().shellEnvironmentVariables(variables).build());
        cfg.setMcp(null);
        cfg.setMemory(RuntimeConfig.MemoryConfig.builder().version(-1).softPromptBudgetTokens(null)
                .maxPromptBudgetTokens(null).workingTopK(null).episodicTopK(null).semanticTopK(null)
                .proceduralTopK(null).promotionEnabled(null).promotionMinConfidence(null).decayEnabled(null)
                .decayDays(null).retrievalLookbackDays(null).codeAwareExtractionEnabled(null).disclosure(null)
                .reranking(null).diagnostics(null).build());
        cfg.setSelfEvolving(selfEvolvingWithBlankNestedValues());

        new LlmConfigService().normalize(cfg);
        new ToolConfigService().normalize(cfg);
        new MemoryConfigService().normalize(cfg);
        new SelfEvolvingConfigService().normalize(cfg);

        assertEquals("feature", cfg.getModelRegistry().getBranch());
        assertEquals(2.0d, cfg.getModelRouter().getRouting().getTemperature());
        assertEquals(5, cfg.getModelRouter().getRouting().getFallbacks().size());
        assertEquals("override", cfg.getTools().getShellEnvironmentVariables().getFirst().getValue());
        assertNotNull(cfg.getMcp());
        assertEquals(RuntimeConfigDefaults.DEFAULT_MEMORY_DISCLOSURE_MODE, cfg.getMemory().getDisclosure().getMode());
        assertEquals(RuntimeConfigDefaults.DEFAULT_MEMORY_RERANKING_PROFILE,
                cfg.getMemory().getReranking().getProfile());
        assertEquals(RuntimeConfigDefaults.DEFAULT_MEMORY_DIAGNOSTICS_VERBOSITY,
                cfg.getMemory().getDiagnostics().getVerbosity());
        assertFalse(cfg.getSelfEvolving().getTactics().getSearch().getEmbeddings().getEnabled());
        assertNull(cfg.getSelfEvolving().getTactics().getSearch().getEmbeddings().getProvider());
        assertTrue(cfg.getSelfEvolving().getEvolution().getModes().contains("suggest"));
        assertEquals("smart", cfg.getSelfEvolving().getJudge().getPrimaryTier());
    }

    private static List<RuntimeConfig.TierFallback> fallbacks() {
        return List.of(RuntimeConfig.TierFallback.builder().build(),
                RuntimeConfig.TierFallback.builder().model("openai/fallback-1").temperature(-1.0d).build(),
                RuntimeConfig.TierFallback.builder().model("openai/fallback-2").build(),
                RuntimeConfig.TierFallback.builder().model("openai/fallback-3").build(),
                RuntimeConfig.TierFallback.builder().model("openai/fallback-4").build(),
                RuntimeConfig.TierFallback.builder().model("openai/fallback-5").build(),
                RuntimeConfig.TierFallback.builder().model("openai/fallback-6").build());
    }

    private static RuntimeConfig.SelfEvolvingConfig selfEvolvingWithBlankNestedValues() {
        RuntimeConfig.SelfEvolvingTacticSearchConfig search = RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                .mode("bm25").bm25(null).embeddings(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig.builder()
                        .provider(" ").baseUrl(" ").model(" ").local(null).build())
                .personalization(null).negativeMemory(null).build();
        return RuntimeConfig.SelfEvolvingConfig.builder().enabled(null).managedByProperties(null).overriddenPaths(null)
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder().enabled(null).search(search).build())
                .capture(RuntimeConfig.SelfEvolvingCaptureConfig.builder().llm("invalid").infra("meta_only").build())
                .judge(RuntimeConfig.SelfEvolvingJudgeConfig.builder().enabled(null).primaryTier("standard")
                        .tiebreakerTier("premium").evolutionTier("invalid").requireEvidenceAnchors(null)
                        .uncertaintyThreshold(2.0d).build())
                .evolution(RuntimeConfig.SelfEvolvingEvolutionConfig.builder().enabled(null)
                        .modes(List.of(" suggest ", "suggest", " ")).artifactTypes(List.of()).build())
                .promotion(RuntimeConfig.SelfEvolvingPromotionConfig.builder().mode(" ").allowAutoAccept(null)
                        .shadowRequired(null).canaryRequired(null).hiveApprovalPreferred(null).build())
                .benchmark(RuntimeConfig.SelfEvolvingBenchmarkConfig.builder().enabled(null).harvestProductionRuns(null)
                        .autoCreateRegressionCases(null).build())
                .hive(RuntimeConfig.SelfEvolvingHiveConfig.builder().publishInspectionProjection(null)
                        .readonlyInspection(null).build())
                .build();
    }

    private static List<RuntimeConfigSectionService> allSectionServices() {
        return List.of(new TelegramConfigService(), new LlmConfigService(), new ToolConfigService(),
                new VoiceConfigService(), new RateLimitConfigService(), new SecurityConfigService(),
                new AutoModeConfigService(), new PlanConfigService(), new SkillConfigService(),
                new UpdateConfigService(), new ObservabilityConfigService(), new SessionRuntimeConfigService(),
                new DelayedActionsConfigService(), new MemoryConfigService(), new ResilienceConfigService(),
                new HiveConfigService(), new SelfEvolvingConfigService());
    }
}
