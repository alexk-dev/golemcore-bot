package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.context.ContextAssembler;
import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.PromptComposer;
import me.golemcore.bot.domain.context.layer.AutoModeLayer;
import me.golemcore.bot.domain.context.layer.HiveLayer;
import me.golemcore.bot.domain.context.layer.IdentityLayer;
import me.golemcore.bot.domain.context.layer.MemoryLayer;
import me.golemcore.bot.domain.context.layer.PlanModeLayer;
import me.golemcore.bot.domain.context.layer.RagLayer;
import me.golemcore.bot.domain.context.layer.SkillLayer;
import me.golemcore.bot.domain.context.layer.TierAwarenessLayer;
import me.golemcore.bot.domain.context.layer.ToolLayer;
import me.golemcore.bot.domain.context.layer.WorkspaceInstructionsLayer;
import me.golemcore.bot.domain.context.resolution.SkillResolver;
import me.golemcore.bot.domain.context.resolution.TierResolver;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.ContextBudgetPolicy;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.SkillTemplateEngine;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.WorkspaceInstructionService;
import me.golemcore.bot.domain.system.toolloop.DefaultHistoryWriter;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.domain.system.toolloop.ToolLoopTurnResult;
import me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.PlanReadyNotificationPort;
import me.golemcore.bot.port.outbound.PlanStorePort;
import me.golemcore.bot.port.outbound.RagPort;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.tools.PlanGetTool;
import me.golemcore.bot.tools.PlanSetContentTool;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BDD-style integration test for the agreed plan-work lifecycle: /plan on ->
 * LLM drafts -> plan_set_content persists SSOT -> plan work stays active ->
 * /plan done disables plan work (tools disappear) but the plan remains READY.
 */
class PlanWorkLifecycleBddTest {

    private static final String CHAT_ID = "chat-1";
    private static final String MODEL_TIER = "smart";
    private static final Instant NOW = Instant.parse("2026-02-15T00:00:00Z");

    @Test
    void shouldPersistCanonicalMarkdownAndKeepPlanWorkActiveUntilPlanDone() {
        // GIVEN: a real PlanService with mocked storage
        PlanStorePort planStorePort = mock(PlanStorePort.class);
        when(planStorePort.loadPlans()).thenReturn(new ArrayList<>());

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isPlanEnabled()).thenReturn(true);
        when(runtimeConfigService.getPlanMaxPlans()).thenReturn(5);
        when(runtimeConfigService.getPlanMaxStepsPerPlan()).thenReturn(50);

        PlanService planService = new PlanService(
                planStorePort,
                runtimeConfigService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        // /plan on (activate plan work + create empty plan)
        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity("telegram", CHAT_ID);
        assertNotNull(sessionIdentity);
        planService.activatePlanMode(sessionIdentity, CHAT_ID, MODEL_TIER);
        String planId = planService.getActivePlanId(sessionIdentity);
        assertNotNull(planId);
        assertTrue(planService.isPlanModeActive(sessionIdentity));

        // And a minimal context builder to verify tool advertisement
        ContextBuildingSystem contextBuildingSystem = buildContextBuildingSystem(planService);

        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId(CHAT_ID).channelType("telegram").messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();

        // WHEN: plan work is active
        contextBuildingSystem.process(context);

        // THEN: plan tools are advertised
        assertTrue(
                context.getAvailableTools().stream().anyMatch(t -> PlanSetContentTool.TOOL_NAME.equals(t.getName())));
        assertTrue(context.getAvailableTools().stream().anyMatch(t -> PlanGetTool.TOOL_NAME.equals(t.getName())));

        // GIVEN: a ToolLoop run that emits plan_set_content tool call
        LlmResponse response1 = LlmResponse.builder()
                .content("Draft ready")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("tc1")
                        .name(PlanSetContentTool.TOOL_NAME).arguments(null)
                        .build()))
                .build();

        // Second LLM call to let ToolLoop finish after synthetic tool results
        LlmResponse response2 = LlmResponse.builder().content("Ok").toolCalls(List.of()).build();

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response1), CompletableFuture.completedFuture(response2));

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveMaxInputTokensForContext(any()))
                .thenReturn(2_000_000_000);
        when(modelSelectionService.resolveForTier(any()))
                .thenReturn(new ModelSelectionService.ModelSelection(null, null));

        DefaultToolLoopSystem toolLoop = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC)))
                .viewBuilder(new DefaultConversationViewBuilder(new FlatteningToolMessageMasker()))
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(new BotProperties.TurnProperties()))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(new BotProperties.ToolLoopProperties()))
                .modelSelectionService(modelSelectionService)
                .planService(planService)
                .contextBudgetPolicy(new ContextBudgetPolicy(null, modelSelectionService))
                .clock(Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC))
                .build();

        // WHEN: ToolLoop processes the turn
        ToolLoopTurnResult turnResult = toolLoop.processTurn(context);

        // THEN: ToolLoop requests finalization downstream; it must not execute the tool
        // directly
        assertTrue(turnResult.finalAnswerReady());
        assertEquals(true, context.getAttribute(ContextAttributes.PLAN_SET_CONTENT_REQUESTED));

        // Force plan_markdown on the tool call (ToolLoop stores the real arguments in
        // raw history;
        // PlanFinalizationSystem reads from LLM_RESPONSE toolCalls).
        LlmResponse ctxResponse = (LlmResponse) context.getAttribute(ContextAttributes.LLM_RESPONSE);
        ctxResponse.setToolCalls(List.of(Message.ToolCall.builder()
                .id("tc1")
                .name(PlanSetContentTool.TOOL_NAME)
                .arguments(Map.of("plan_markdown", "# Plan\n\n1) Do X\n2) Do Y\n", "title", "My plan"))
                .build()));

        verify(toolExecutor, never()).execute(any(), any());

        // WHEN: PlanFinalizationSystem runs
        PlanReadyNotificationPort planReadyNotificationPort = mock(PlanReadyNotificationPort.class);
        PlanFinalizationSystem planFinalizationSystem = new PlanFinalizationSystem(planService,
                planReadyNotificationPort);
        assertTrue(planFinalizationSystem.shouldProcess(context));
        planFinalizationSystem.process(context);

        // THEN: plan is READY with canonical markdown, but plan work is still active
        Optional<Plan> plan = planService.getPlan(planId);
        assertTrue(plan.isPresent());
        assertEquals(Plan.PlanStatus.READY, plan.get().getStatus());
        assertEquals("My plan", plan.get().getTitle());
        assertEquals("# Plan\n\n1) Do X\n2) Do Y\n", plan.get().getMarkdown());
        assertTrue(planService.isPlanModeActive(sessionIdentity));

        // WHEN: /plan done (deactivate plan work)
        planService.deactivatePlanMode(sessionIdentity);
        assertFalse(planService.isPlanModeActive(sessionIdentity));

        // THEN: tools are no longer advertised, but plan stays READY
        contextBuildingSystem.process(context);
        assertTrue(context.getAvailableTools().stream()
                .noneMatch(t -> PlanSetContentTool.TOOL_NAME.equals(t.getName())
                        || PlanGetTool.TOOL_NAME.equals(t.getName())));

        plan = planService.getPlan(planId);
        assertTrue(plan.isPresent());
        assertEquals(Plan.PlanStatus.READY, plan.get().getStatus());
    }

    private static ContextBuildingSystem buildContextBuildingSystem(PlanService planService) {
        SkillTemplateEngine templateEngine = mock(SkillTemplateEngine.class);
        PromptSectionService promptSectionService = mock(PromptSectionService.class);
        UserPreferencesService userPreferencesService = mock(UserPreferencesService.class);
        when(userPreferencesService.getPreferences())
                .thenReturn(me.golemcore.bot.domain.model.UserPreferences.builder().build());

        MemoryComponent memoryComponent = mock(MemoryComponent.class);
        SkillComponent skillComponent = mock(SkillComponent.class);
        ToolCallExecutionService toolCallExecutionService = mock(ToolCallExecutionService.class);
        McpPort mcpPort = mock(McpPort.class);
        RagPort ragPort = mock(RagPort.class);
        AutoModeService autoModeService = mock(AutoModeService.class);
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveMaxInputTokensForContext(any()))
                .thenReturn(2_000_000_000);
        when(modelSelectionService.resolveForTier(any()))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-balanced", "medium"));
        WorkspaceInstructionService workspaceInstructionService = mock(WorkspaceInstructionService.class);
        when(workspaceInstructionService.getWorkspaceInstructionsContext()).thenReturn("");
        PlanSetContentTool planSetContentTool = new PlanSetContentTool(planService);
        PlanGetTool planGetTool = new PlanGetTool(planService);
        when(toolCallExecutionService.listTools()).thenReturn(List.of(planSetContentTool, planGetTool));

        SkillResolver skillResolver = new SkillResolver(skillComponent);
        TierResolver tierResolver = new TierResolver(
                userPreferencesService, modelSelectionService, runtimeConfigService, skillComponent);

        List<ContextLayer> layers = List.of(
                new IdentityLayer(promptSectionService, userPreferencesService),
                new WorkspaceInstructionsLayer(workspaceInstructionService),
                new MemoryLayer(memoryComponent, runtimeConfigService, new MemoryPresetService()),
                new RagLayer(ragPort),
                new SkillLayer(skillComponent, templateEngine),
                new ToolLayer(toolCallExecutionService, mcpPort, planService, delayedActionPolicyService),
                new TierAwarenessLayer(userPreferencesService),
                new AutoModeLayer(autoModeService),
                new PlanModeLayer(planService),
                new HiveLayer());

        ContextAssembler contextAssembler = new ContextAssembler(
                skillResolver, tierResolver, layers, new PromptComposer());

        return new ContextBuildingSystem(contextAssembler, null, null, null);
    }
}
