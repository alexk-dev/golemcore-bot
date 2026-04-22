package me.golemcore.bot.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import me.golemcore.bot.adapter.inbound.webhook.WebhookChannelAdapter;
import me.golemcore.bot.domain.context.layer.TokenEstimator;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.MemoryPresetIds;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionInspectionService;
import me.golemcore.bot.domain.view.SessionTraceExportView;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.infrastructure.telemetry.TelemetryEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(GolemCoreBotIntegrationTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "AGENTLOOP_E2E_ENABLED", matches = "true")
class AgentLoopContextWindowExternalE2ETest {

    private static final int DEFAULT_MAX_INPUT_TOKENS = 16_000;
    private static final int DEFAULT_PAYLOAD_KB = 16;
    private static final int DEFAULT_TIMEOUT_SECONDS = 180;
    private static final int REQUEST_ESTIMATE_TOLERANCE_TOKENS = 1_024;
    private static final int REQUEST_BASE_OVERHEAD_TOKENS = 256;
    private static final int MAX_PROMPT_CATALOG_TOOLS = 24;
    private static final String LOW_PRIORITY_TAIL_MARKER = "WORKSPACE_NOISE_LINE_0499";
    private static final Set<String> DISABLED_TOOL_NAMES = Set.of(
            "filesystem",
            "shell",
            "skill_management",
            "skill_transition",
            "set_tier",
            "goal_management",
            "memory",
            "discover_mcp_server",
            "send_voice",
            "plan_get",
            "plan_set_content");

    @TempDir
    static Path tempDir;

    @Autowired
    private AgentLoop agentLoop;

    @Autowired
    private WebhookChannelAdapter webhookChannelAdapter;

    @Autowired
    private RuntimeConfigService runtimeConfigService;

    @Autowired
    private ModelConfigService modelConfigService;

    @Autowired
    private SessionInspectionService sessionInspectionService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    TelemetryEventPublisher telemetryEventPublisher;

    private String providerId;
    private String catalogModelId;
    private int maxInputTokens;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("bot.storage.local.base-path", () -> tempDir.resolve("workspace").toString());
        registry.add("bot.tools.filesystem.workspace", () -> tempDir.resolve("sandbox").toString());
        registry.add("bot.tools.shell.workspace", () -> tempDir.resolve("sandbox").toString());
        registry.add("bot.dashboard.enabled", () -> "false");
        registry.add("bot.llm.provider", () -> "langchain4j");
        registry.add("bot.plugins.enabled", () -> "false");
        registry.add("bot.plugins.auto-start", () -> "false");
        registry.add("bot.plugins.auto-reload", () -> "false");
        registry.add("bot.update.enabled", () -> "false");
        registry.add("bot.skills.marketplace-enabled", () -> "false");
        registry.add("bot.self-evolving.bootstrap.enabled", () -> "false");
        registry.add("bot.self-evolving.bootstrap.tactics.enabled", () -> "false");
        registry.add("bot.self-evolving.bootstrap.tactics.search.mode", () -> "bm25");
        registry.add("bot.self-evolving.bootstrap.tactics.search.embeddings.local.auto-install", () -> "false");
        registry.add("bot.self-evolving.bootstrap.tactics.search.embeddings.local.pull-on-start", () -> "false");
    }

    @BeforeEach
    void configureRuntime() throws Exception {
        Files.createDirectories(tempDir.resolve("sandbox"));
        Files.writeString(tempDir.resolve("sandbox").resolve("AGENTS.md"), oversizedWorkspaceInstructions());

        providerId = optionalEnv("AGENTLOOP_E2E_PROVIDER", "openai");
        String providerModel = requiredEnv("AGENTLOOP_E2E_MODEL");
        catalogModelId = canonicalCatalogModelId(providerId, providerModel);
        maxInputTokens = intEnv("AGENTLOOP_E2E_MAX_INPUT_TOKENS", DEFAULT_MAX_INPUT_TOKENS);

        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider(providerId);
        settings.setDisplayName("AgentLoop E2E model");
        settings.setSupportsVision(false);
        settings.setSupportsTemperature(booleanEnv("AGENTLOOP_E2E_SUPPORTS_TEMPERATURE", true));
        settings.setMaxInputTokens(maxInputTokens);
        modelConfigService.saveModel(catalogModelId, settings);

        runtimeConfigService.updateRuntimeConfig(buildRuntimeConfig());
    }

    @Test
    void shouldBudgetContextWindowThroughAgentLoopWithOpenAiCompatibleApi() throws Exception {
        String marker = "AGENTLOOP_CONTEXT_WINDOW_E2E_" + UUID.randomUUID().toString().replace("-", "");
        String chatId = "context-window-e2e-" + UUID.randomUUID();
        String runId = "run-" + UUID.randomUUID();
        CompletableFuture<String> responseFuture = webhookChannelAdapter.registerPendingRun(
                chatId, runId, null, "balanced");

        Message inbound = Message.builder()
                .role("user")
                .content(userPrompt(marker))
                .channelType(ChannelTypes.WEBHOOK)
                .chatId(chatId)
                .senderId("agentloop-context-window-e2e")
                .timestamp(Instant.now())
                .metadata(new LinkedHashMap<>(Map.of(
                        ContextAttributes.MEMORY_PRESET_ID, MemoryPresetIds.DISABLED,
                        ContextAttributes.WEBHOOK_MODEL_TIER, "balanced")))
                .build();

        AgentContext context = agentLoop.processMessage(inbound);
        assertNotNull(context);
        assertNull(context.getAttribute(ContextAttributes.LLM_ERROR));
        assertTrue(Boolean.TRUE.equals(context.getAttribute(ContextAttributes.FINAL_ANSWER_READY)));
        Map<String, Object> hygieneReport = context.getAttribute(ContextAttributes.CONTEXT_HYGIENE_REPORT);
        assertNotNull(hygieneReport, "request-time context hygiene report should be recorded");
        assertTrue(numberValue(hygieneReport, "projectedTokens") <= numberValue(hygieneReport, "rawTokens"),
                () -> "projected context should not exceed raw context: " + hygieneReport);
        assertContextHygieneEventEmitted(context, hygieneReport);

        String response = responseFuture.get(intEnv("AGENTLOOP_E2E_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS),
                TimeUnit.SECONDS);
        assertNotNull(response);
        assertTrue(response.contains(marker), () -> "Expected response to contain marker " + marker
                + ", actual response: " + response);

        String requestPayload = latestLlmRequestPayload("webhook:" + chatId);
        JsonNode request = objectMapper.readTree(requestPayload);
        String systemPrompt = request.path("systemPrompt").asText();
        int systemPromptTokens = TokenEstimator.estimate(systemPrompt);
        int expectedBudget = expectedSystemPromptBudget(maxInputTokens);
        int estimatedRequestTokens = estimateRequestTokens(request);
        int expectedInputBudget = expectedFullRequestBudget(maxInputTokens);

        assertFalse(systemPrompt.isBlank());
        assertTrue(systemPromptTokens <= expectedBudget + 100,
                () -> "systemPrompt estimate " + systemPromptTokens + " exceeded budget " + expectedBudget);
        assertTrue(estimatedRequestTokens <= expectedInputBudget + REQUEST_ESTIMATE_TOLERANCE_TOKENS,
                () -> "request estimate " + estimatedRequestTokens + " exceeded input budget " + expectedInputBudget);
        assertFalse(systemPrompt.contains(LOW_PRIORITY_TAIL_MARKER),
                "low-priority workspace instruction tail should be dropped by layer/global budgeting");
        assertBoundedTools(request.path("tools"), systemPrompt);
        assertTrue(request.path("messages").isArray());
        assertTrue(request.toString().contains(marker));
    }

    private RuntimeConfig buildRuntimeConfig() {
        String reasoning = blankToNull(System.getenv("AGENTLOOP_E2E_REASONING"));
        Map<String, RuntimeConfig.LlmProviderConfig> providers = new LinkedHashMap<>();
        providers.put(providerId, RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of(requiredEnv("AGENTLOOP_E2E_OPENAI_API_KEY")))
                .baseUrl(requiredEnv("AGENTLOOP_E2E_OPENAI_BASE_URL"))
                .apiType("openai")
                .legacyApi(booleanEnv("AGENTLOOP_E2E_LEGACY_API", true))
                .requestTimeoutSeconds(intEnv("AGENTLOOP_E2E_REQUEST_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS))
                .build());

        return RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(providers)
                        .build())
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder()
                        .routingModel(catalogModelId)
                        .routingModelReasoning(reasoning)
                        .balancedModel(catalogModelId)
                        .balancedModelReasoning(reasoning)
                        .smartModel(catalogModelId)
                        .smartModelReasoning(reasoning)
                        .codingModel(catalogModelId)
                        .codingModelReasoning(reasoning)
                        .deepModel(catalogModelId)
                        .deepModelReasoning(reasoning)
                        .dynamicTierEnabled(false)
                        .build())
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .filesystemEnabled(false)
                        .shellEnabled(false)
                        .skillManagementEnabled(false)
                        .skillTransitionEnabled(false)
                        .tierEnabled(false)
                        .goalManagementEnabled(false)
                        .build())
                .tracing(RuntimeConfig.TracingConfig.builder()
                        .enabled(true)
                        .payloadSnapshotsEnabled(true)
                        .captureInboundPayloads(true)
                        .captureOutboundPayloads(true)
                        .captureToolPayloads(true)
                        .captureLlmPayloads(true)
                        .maxSnapshotSizeKb(intEnv("AGENTLOOP_E2E_MAX_SNAPSHOT_KB", 1024))
                        .maxSnapshotsPerSpan(10)
                        .sessionTraceBudgetMb(64)
                        .maxTracesPerSession(20)
                        .build())
                .rateLimit(RuntimeConfig.RateLimitConfig.builder()
                        .enabled(false)
                        .build())
                .security(RuntimeConfig.SecurityConfig.builder()
                        .sanitizeInput(false)
                        .detectPromptInjection(false)
                        .detectCommandInjection(false)
                        .maxInputLength(256_000)
                        .allowlistEnabled(false)
                        .toolConfirmationEnabled(false)
                        .build())
                .compaction(RuntimeConfig.CompactionConfig.builder()
                        .enabled(true)
                        .maxContextTokens(maxInputTokens)
                        .keepLastMessages(4)
                        .triggerMode("model_ratio")
                        .modelThresholdRatio(0.95d)
                        .preserveTurnBoundaries(true)
                        .detailsEnabled(true)
                        .summaryTimeoutMs(15_000)
                        .build())
                .turn(RuntimeConfig.TurnConfig.builder()
                        .maxLlmCalls(1)
                        .maxToolExecutions(0)
                        .deadline("PT3M")
                        .autoRetryEnabled(false)
                        .progressUpdatesEnabled(false)
                        .progressIntentEnabled(false)
                        .queueSteeringEnabled(false)
                        .build())
                .memory(RuntimeConfig.MemoryConfig.builder()
                        .enabled(false)
                        .build())
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .enabled(false)
                        .progressiveLoading(false)
                        .build())
                .usage(RuntimeConfig.UsageConfig.builder()
                        .enabled(false)
                        .build())
                .telemetry(RuntimeConfig.TelemetryConfig.builder()
                        .enabled(false)
                        .build())
                .mcp(RuntimeConfig.McpConfig.builder()
                        .enabled(false)
                        .build())
                .plan(RuntimeConfig.PlanConfig.builder()
                        .enabled(false)
                        .build())
                .hive(RuntimeConfig.HiveConfig.builder()
                        .enabled(false)
                        .autoConnect(false)
                        .managedByProperties(false)
                        .build())
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(false)
                        .build())
                .resilience(RuntimeConfig.ResilienceConfig.builder()
                        .enabled(false)
                        .build())
                .build();
    }

    private String latestLlmRequestPayload(String sessionId) {
        SessionTraceExportView export = sessionInspectionService.getSessionTraceExport(sessionId);
        if (export.getTraces() == null) {
            throw new NoSuchElementException("No traces captured for " + sessionId);
        }
        return export.getTraces().stream()
                .flatMap(
                        trace -> trace.getSpans() != null ? trace.getSpans().stream() : java.util.stream.Stream.empty())
                .filter(span -> "llm.chat".equals(span.getName()))
                .sorted(Comparator.comparing(SessionTraceExportView.SpanExportView::getStartedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .flatMap(span -> span.getSnapshots() != null
                        ? span.getSnapshots().stream()
                        : java.util.stream.Stream.empty())
                .filter(snapshot -> "request".equals(snapshot.getRole()))
                .map(SessionTraceExportView.SnapshotExportView::getPayloadText)
                .filter(payload -> payload != null && !payload.isBlank())
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No LLM request snapshot captured for " + sessionId));
    }

    private String userPrompt(String marker) {
        return """
                CI context-window verification.

                Return exactly this verification code as plain text, with no extra explanation:
                %s

                Large irrelevant payload follows. It exists only to exercise request budgeting.

                %s
                """.formatted(marker, repeatedPayload("USER_PAYLOAD_LINE_", intEnv("AGENTLOOP_E2E_PAYLOAD_KB",
                DEFAULT_PAYLOAD_KB) * 1024));
    }

    private String oversizedWorkspaceInstructions() {
        StringBuilder sb = new StringBuilder();
        sb.append("# AgentLoop E2E Workspace Instructions\n\n");
        sb.append("These instructions are intentionally repetitive so prompt budgeting has to trim this layer.\n\n");
        for (int i = 0; i < 500; i++) {
            sb.append("WORKSPACE_NOISE_LINE_")
                    .append(String.format(Locale.ROOT, "%04d", i))
                    .append(": low priority repository guidance filler for context budgeting checks.\n");
        }
        return sb.toString();
    }

    private String repeatedPayload(String prefix, int targetChars) {
        StringBuilder sb = new StringBuilder(targetChars + 128);
        int index = 0;
        while (sb.length() < targetChars) {
            String formattedIndex = String.format(Locale.ROOT, "%04d", index);
            sb.append(prefix)
                    .append(formattedIndex)
                    .append(" repeatable irrelevant data for context-window preflight.\n");
            index++;
        }
        return sb.toString();
    }

    private String canonicalCatalogModelId(String provider, String model) {
        String normalizedProvider = provider.trim();
        String normalizedModel = model.trim();
        if (normalizedModel.startsWith(normalizedProvider + "/")) {
            return normalizedModel;
        }
        return normalizedProvider + "/" + normalizedModel;
    }

    private int expectedSystemPromptBudget(int modelMax) {
        int fullRequestThreshold = expectedFullRequestBudget(modelMax);
        return Math.min(12_000, Math.max(1_500, (int) Math.floor(fullRequestThreshold * 0.35d)));
    }

    private int expectedFullRequestBudget(int modelMax) {
        int proportionalReserve = Math.max(1024, (int) Math.floor(modelMax * 0.05d));
        int cappedReserve = Math.min(32_768, proportionalReserve);
        int maximumReserve = Math.max(1, modelMax / 4);
        int outputReserve = Math.min(cappedReserve, maximumReserve);
        int modelSafeThreshold = Math.max(1, modelMax - outputReserve);
        int ratioThreshold = Math.max(1, (int) Math.floor(modelMax * 0.95d));
        return Math.min(ratioThreshold, modelSafeThreshold);
    }

    private int estimateRequestTokens(JsonNode request) {
        int tokens = REQUEST_BASE_OVERHEAD_TOKENS;
        tokens += TokenEstimator.estimate(request.path("systemPrompt").asText());
        tokens += TokenEstimator.estimate(request.path("messages").toString());
        tokens += TokenEstimator.estimate(request.path("tools").toString());
        tokens += TokenEstimator.estimate(request.path("toolResults").toString());
        return tokens;
    }

    private void assertBoundedTools(JsonNode tools, String systemPrompt) {
        assertTrue(tools.isArray());
        assertTrue(tools.size() <= MAX_PROMPT_CATALOG_TOOLS,
                () -> "tool schemas should stay bounded in isolated AgentLoop E2E: " + tools.size());
        if (tools.size() > 0) {
            assertTrue(systemPrompt.contains("# Tool Use Policy"),
                    "tool schemas require the compact tool-use policy in the system prompt");
        }
        for (String disabledToolName : DISABLED_TOOL_NAMES) {
            assertFalse(containsToolName(tools, disabledToolName),
                    () -> "disabled tool should not be advertised: " + disabledToolName);
        }
    }

    private boolean containsToolName(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            String toolName = toolName(tool);
            if (name.equals(toolName)) {
                return true;
            }
        }
        return false;
    }

    private String toolName(JsonNode tool) {
        String directName = tool.path("name").asText("");
        if (!directName.isBlank()) {
            return directName;
        }
        return tool.path("function").path("name").asText("");
    }

    private void assertContextHygieneEventEmitted(AgentContext context, Map<String, Object> hygieneReport) {
        List<RuntimeEvent> runtimeEvents = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertNotNull(runtimeEvents, "runtime events should be recorded");
        assertTrue(runtimeEvents.stream().anyMatch(event -> isMatchingContextHygieneEvent(event, hygieneReport)),
                () -> "CONTEXT_HYGIENE runtime event should include report: " + hygieneReport);
    }

    private boolean isMatchingContextHygieneEvent(RuntimeEvent event, Map<String, Object> hygieneReport) {
        if (event == null || event.type() != RuntimeEventType.CONTEXT_HYGIENE || event.payload() == null) {
            return false;
        }
        Object report = event.payload().get("contextHygiene");
        if (!(report instanceof Map<?, ?> eventReport)) {
            return false;
        }
        Object projectedTokens = eventReport.get("projectedTokens");
        return projectedTokens instanceof Number projected
                && projected.intValue() == numberValue(hygieneReport, "projectedTokens");
    }

    private String requiredEnv(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "Required environment variable is missing: " + name);
        return value;
    }

    private String optionalEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }

    private boolean booleanEnv(String name, boolean defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int numberValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        assertTrue(value instanceof Number, "Expected numeric hygiene report value for " + key);
        return ((Number) value).intValue();
    }
}
