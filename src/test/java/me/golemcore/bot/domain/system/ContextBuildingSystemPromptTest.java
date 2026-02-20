package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SkillTemplateEngine;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.WorkspaceInstructionService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.RagPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextBuildingSystemPromptTest {

    private static final String SECTION_IDENTITY = "identity";
    private static final String SKILL_PROCESSING = "processing";
    private static final String SKILL_CODE_REVIEW = "code-review";
    private static final String TIER_SMART = "smart";
    private static final String TIER_DEEP = "deep";
    private static final String TIER_CODING = "coding";
    private static final String SKILL_TEST = "test";
    private static final String CONTENT_TEST = "test";

    private MemoryComponent memoryComponent;
    private SkillComponent skillComponent;
    private SkillTemplateEngine templateEngine;
    private McpPort mcpPort;
    private ToolCallExecutionService toolCallExecutionService;
    private RagPort ragPort;
    private BotProperties properties;
    private AutoModeService autoModeService;
    private PlanService planService;
    private PromptSectionService promptSectionService;
    private RuntimeConfigService runtimeConfigService;
    private UserPreferencesService userPreferencesService;
    private WorkspaceInstructionService workspaceInstructionService;
    private ContextBuildingSystem system;

    @BeforeEach
    void setUp() {
        memoryComponent = mock(MemoryComponent.class);
        skillComponent = mock(SkillComponent.class);
        templateEngine = new SkillTemplateEngine();
        mcpPort = mock(McpPort.class);
        toolCallExecutionService = mock(ToolCallExecutionService.class);
        ragPort = mock(RagPort.class);
        properties = new BotProperties();
        autoModeService = mock(AutoModeService.class);
        planService = mock(PlanService.class);
        promptSectionService = mock(PromptSectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        userPreferencesService = mock(UserPreferencesService.class);
        workspaceInstructionService = mock(WorkspaceInstructionService.class);
        when(runtimeConfigService.getAutoModelTier()).thenReturn(TIER_SMART);

        when(memoryComponent.getMemoryContext()).thenReturn("");
        when(skillComponent.getSkillsSummary()).thenReturn("");
        when(ragPort.isAvailable()).thenReturn(false);
        when(workspaceInstructionService.getWorkspaceInstructionsContext()).thenReturn("");
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().build());

        system = new ContextBuildingSystem(
                memoryComponent,
                skillComponent,
                List.of(),
                templateEngine,
                mcpPort,
                toolCallExecutionService,
                ragPort,
                properties,
                autoModeService,
                planService,
                promptSectionService,
                runtimeConfigService,
                userPreferencesService,
                workspaceInstructionService);
    }

    private AgentContext createContext() {
        return AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("Hello").timestamp(Instant.now()).build())))
                .build();
    }

    @Test
    void buildSystemPrompt_withSections() {
        when(promptSectionService.isEnabled()).thenReturn(true);
        when(promptSectionService.buildTemplateVariables(any()))
                .thenReturn(Map.of("BOT_NAME", "TestBot"));
        when(promptSectionService.getEnabledSections()).thenReturn(List.of(
                PromptSection.builder().name(SECTION_IDENTITY).content("You are TestBot.").order(10).build(),
                PromptSection.builder().name("rules").content("Be helpful.").order(20).build()));
        when(promptSectionService.renderSection(any(), any()))
                .thenAnswer(inv -> {
                    PromptSection s = inv.getArgument(0);
                    return s.getContent();
                });

        AgentContext ctx = createContext();
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.contains("You are TestBot."));
        assertTrue(prompt.contains("Be helpful."));
        assertFalse(prompt.contains("You are a helpful AI assistant."));
    }

    @Test
    void buildSystemPrompt_disabledFallback() {
        when(promptSectionService.isEnabled()).thenReturn(false);

        AgentContext ctx = createContext();
        system.process(ctx);

        assertTrue(ctx.getSystemPrompt().contains("You are a helpful AI assistant."));
    }

    @Test
    void buildSystemPrompt_noSectionsFallback() {
        when(promptSectionService.isEnabled()).thenReturn(true);
        when(promptSectionService.buildTemplateVariables(any())).thenReturn(Map.of());
        when(promptSectionService.getEnabledSections()).thenReturn(List.of());

        AgentContext ctx = createContext();
        system.process(ctx);

        assertTrue(ctx.getSystemPrompt().contains("You are a helpful AI assistant."));
    }

    @Test
    void buildSystemPrompt_correctOrder() {
        when(promptSectionService.isEnabled()).thenReturn(true);
        when(promptSectionService.buildTemplateVariables(any())).thenReturn(Map.of());
        when(promptSectionService.getEnabledSections()).thenReturn(List.of(
                PromptSection.builder().name(SECTION_IDENTITY).content("IDENTITY_MARKER").order(10).build(),
                PromptSection.builder().name("rules").content("RULES_MARKER").order(20).build()));
        when(promptSectionService.renderSection(any(), any()))
                .thenAnswer(inv -> ((PromptSection) inv.getArgument(0)).getContent());

        AgentContext ctx = createContext();
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.indexOf("IDENTITY_MARKER") < prompt.indexOf("RULES_MARKER"));
    }

    @Test
    void buildSystemPrompt_templateRendered() {
        when(promptSectionService.isEnabled()).thenReturn(true);
        when(promptSectionService.buildTemplateVariables(any()))
                .thenReturn(Map.of("BOT_NAME", "SuperBot"));
        when(promptSectionService.getEnabledSections()).thenReturn(List.of(
                PromptSection.builder().name(SECTION_IDENTITY).content("I am {{BOT_NAME}}").order(10).build()));
        when(promptSectionService.renderSection(any(), any())).thenReturn("I am SuperBot");

        AgentContext ctx = createContext();
        system.process(ctx);

        assertTrue(ctx.getSystemPrompt().contains("I am SuperBot"));
        assertFalse(ctx.getSystemPrompt().contains("{{BOT_NAME}}"));
    }

    @Test
    void buildSystemPrompt_dynamicSectionsPreserved() {
        when(promptSectionService.isEnabled()).thenReturn(true);
        when(promptSectionService.buildTemplateVariables(any())).thenReturn(Map.of());
        when(promptSectionService.getEnabledSections()).thenReturn(List.of(
                PromptSection.builder().name(SECTION_IDENTITY).content("Bot identity").order(10).build()));
        when(promptSectionService.renderSection(any(), any())).thenReturn("Bot identity");

        // Set up memory and tools
        when(memoryComponent.getMemoryContext()).thenReturn("User prefers concise answers.");

        ToolComponent tool = mock(ToolComponent.class);
        when(tool.isEnabled()).thenReturn(true);
        when(tool.getDefinition()).thenReturn(ToolDefinition.builder()
                .name("test_tool")
                .description("A test tool")
                .inputSchema(Map.of("type", "object"))
                .build());

        system = new ContextBuildingSystem(
                memoryComponent,
                skillComponent,
                List.of(tool),
                templateEngine,
                mcpPort,
                toolCallExecutionService,
                ragPort,
                properties,
                autoModeService,
                planService,
                promptSectionService,
                runtimeConfigService,
                userPreferencesService,
                workspaceInstructionService);

        AgentContext ctx = createContext();
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.contains("Bot identity"));
        assertTrue(prompt.contains("# Memory"));
        assertTrue(prompt.contains("User prefers concise answers."));
        assertTrue(prompt.contains("# Available Tools"));
        assertTrue(prompt.contains("test_tool"));
    }

    @Test
    void injectsWorkspaceInstructionsWhenAvailable() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(workspaceInstructionService.getWorkspaceInstructionsContext()).thenReturn("""
                ## AGENTS.md
                Root instructions
                                
                ## dashboard/CLAUDE.md
                Local dashboard instructions
                """);

        AgentContext ctx = createContext();
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.contains("# Workspace Instructions"));
        assertTrue(prompt.contains("Root instructions"));
        assertTrue(prompt.contains("Local dashboard instructions"));
    }

    // ===== Active skill injection =====

    @Test
    void injectsActiveSkillContent() {
        when(promptSectionService.isEnabled()).thenReturn(false);

        Skill skill = Skill.builder()
                .name(SKILL_CODE_REVIEW)
                .description("Review code")
                .content("You are a code reviewer. Analyze code carefully.")
                .available(true)
                .build();

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.contains("# Active Skill: " + SKILL_CODE_REVIEW));
        assertTrue(prompt.contains("You are a code reviewer"));
    }

    @Test
    void injectsSkillsSummaryWhenNoActiveSkill() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(skillComponent.getSkillsSummary()).thenReturn("- greeting: Handle greetings\n- coding: Code tasks");

        AgentContext ctx = createContext();
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.contains("# Available Skills"));
        assertTrue(prompt.contains("greeting: Handle greetings"));
    }

    // ===== Skill pipeline info =====

    @Test
    void injectsPipelineInfoForSkillWithTransitions() {
        when(promptSectionService.isEnabled()).thenReturn(false);

        Skill skill = Skill.builder()
                .name("intake")
                .description("Intake skill")
                .content("Collect user info")
                .nextSkill(SKILL_PROCESSING)
                .conditionalNextSkills(Map.of("error", "error-handler"))
                .available(true)
                .build();

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.contains("# Skill Pipeline"));
        assertTrue(prompt.contains("Default next: processing"));
        assertTrue(prompt.contains("error"));
        assertTrue(prompt.contains("error-handler"));
    }

    // ===== RAG context =====

    @Test
    void injectsRagContextWhenAvailable() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(ragPort.isAvailable()).thenReturn(true);
        when(runtimeConfigService.getRagQueryMode()).thenReturn("hybrid");
        when(ragPort.query(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture("User discussed Java projects last week."));

        AgentContext ctx = createContext();
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.contains("# Relevant Memory"));
        assertTrue(prompt.contains("User discussed Java projects"));
    }

    @Test
    void skipsRagWhenUnavailable() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(ragPort.isAvailable()).thenReturn(false);

        AgentContext ctx = createContext();
        system.process(ctx);

        assertFalse(ctx.getSystemPrompt().contains("# Relevant Memory"));
        verify(ragPort, never()).query(anyString(), anyString());
    }

    // ===== MCP tools =====

    @Test
    void registersMcpToolsForActiveSkillWithMcp() {
        when(promptSectionService.isEnabled()).thenReturn(false);

        McpConfig mcpConfig = McpConfig.builder().command("npx server").build();
        Skill skill = Skill.builder()
                .name("github")
                .description("GitHub skill")
                .content("Interact with GitHub")
                .mcpConfig(mcpConfig)
                .available(true)
                .build();

        ToolDefinition mcpTool = ToolDefinition.builder()
                .name("github_search")
                .description("Search GitHub")
                .build();
        when(mcpPort.getOrStartClient(skill)).thenReturn(List.of(mcpTool));

        ToolComponent mcpAdapter = mock(ToolComponent.class);
        when(mcpPort.createToolAdapter(eq("github"), eq(mcpTool))).thenReturn(mcpAdapter);

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        verify(toolCallExecutionService).registerTool(mcpAdapter);
        assertTrue(ctx.getAvailableTools().contains(mcpTool));
        assertTrue(ctx.getSystemPrompt().contains("github_search"));
    }

    // ===== Skill transition =====

    @Test
    void handlesSkillTransition() {
        when(promptSectionService.isEnabled()).thenReturn(false);

        Skill targetSkill = Skill.builder()
                .name(SKILL_PROCESSING)
                .description("Process data")
                .content("You process data")
                .available(true)
                .build();
        when(skillComponent.findByName(SKILL_PROCESSING)).thenReturn(Optional.of(targetSkill));

        AgentContext ctx = createContext();
        ctx.setSkillTransitionRequest(me.golemcore.bot.domain.model.SkillTransitionRequest.explicit(SKILL_PROCESSING));
        system.process(ctx);

        assertEquals(targetSkill, ctx.getActiveSkill());
        assertNull(ctx.getSkillTransitionRequest()); // cleared after transition
        assertTrue(ctx.getSystemPrompt().contains("# Active Skill: processing"));
    }

    // ===== Auto mode =====

    @Test
    void setsAutoModeTierForAutoMessages() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        Map<String, Object> meta = new HashMap<>();
        meta.put("auto.mode", true);
        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().role("user").content("Auto task").timestamp(Instant.now()).metadata(meta).build()));
        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(messages)
                .build();

        when(autoModeService.buildAutoContext()).thenReturn("# Goals\n- Build something");

        system.process(ctx);

        assertEquals(TIER_SMART, ctx.getModelTier());
        assertTrue(ctx.getSystemPrompt().contains("# Goals"));
    }

    // ===== Plan mode context =====

    @Test
    void injectsPlanContextWhenPlanModeActive() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.buildPlanContext()).thenReturn("# Plan Mode\nCollecting tool calls for plan.");
        when(planService.getActivePlan()).thenReturn(Optional.empty());

        AgentContext ctx = createContext();
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.contains("# Plan Mode"));
        assertTrue(prompt.contains("Collecting tool calls for plan."));
    }

    @Test
    void setsPlanModelTierWhenPlanHasTier() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.buildPlanContext()).thenReturn("Plan context");
        Plan plan = Plan.builder().id("p1").modelTier(TIER_DEEP).build();
        when(planService.getActivePlan()).thenReturn(Optional.of(plan));

        AgentContext ctx = createContext();
        system.process(ctx);

        assertEquals(TIER_DEEP, ctx.getModelTier());
    }

    @Test
    void doesNotOverrideExistingModelTierFromPlan() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.buildPlanContext()).thenReturn("Plan context");
        Plan plan = Plan.builder().id("p1").modelTier(TIER_DEEP).build();
        when(planService.getActivePlan()).thenReturn(Optional.of(plan));

        AgentContext ctx = createContext();
        ctx.setModelTier(TIER_CODING);
        system.process(ctx);

        assertEquals(TIER_CODING, ctx.getModelTier());
    }

    @Test
    void skipsPlanContextWhenPlanModeInactive() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(planService.isPlanModeActive()).thenReturn(false);

        AgentContext ctx = createContext();
        system.process(ctx);

        verify(planService, never()).buildPlanContext();
        assertFalse(ctx.getSystemPrompt().contains("Plan"));
    }

    @Test
    void skipsPlanContextWhenBuildReturnsBlank() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.buildPlanContext()).thenReturn("   ");
        when(planService.getActivePlan()).thenReturn(Optional.empty());

        AgentContext ctx = createContext();
        system.process(ctx);

        // Blank plan context should not appear in prompt
        String prompt = ctx.getSystemPrompt();
        assertFalse(prompt.contains("Plan Mode"));
    }

    @Test
    void skipsPlanContextWhenBuildReturnsNull() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.buildPlanContext()).thenReturn(null);
        when(planService.getActivePlan()).thenReturn(Optional.empty());

        AgentContext ctx = createContext();
        system.process(ctx);

        assertFalse(ctx.getSystemPrompt().contains("Plan"));
    }

    // ===== resolveTier =====

    @Test
    void resolveTier_skipsWhenIterationNotZero() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(userPreferencesService.getPreferences()).thenReturn(
                UserPreferences.builder().modelTier(TIER_SMART).build());

        AgentContext ctx = createContext();
        ctx.setCurrentIteration(2);
        system.process(ctx);

        assertNull(ctx.getModelTier());
    }

    @Test
    void resolveTier_forceTierTakesPriority() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(userPreferencesService.getPreferences()).thenReturn(
                UserPreferences.builder().modelTier(TIER_SMART).tierForce(true).build());

        Skill skill = Skill.builder().name(SKILL_TEST).description(CONTENT_TEST)
                .content(CONTENT_TEST).modelTier(TIER_CODING).available(true).build();

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        assertEquals(TIER_SMART, ctx.getModelTier());
    }

    @Test
    void resolveTier_skillTierOverridesUserTier() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(userPreferencesService.getPreferences()).thenReturn(
                UserPreferences.builder().modelTier("balanced").build());

        Skill skill = Skill.builder().name("coder").description("code")
                .content("instructions").modelTier(TIER_CODING).available(true).build();

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        assertEquals(TIER_CODING, ctx.getModelTier());
    }

    @Test
    void resolveTier_userTierFallbackWhenSkillHasNoTier() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(userPreferencesService.getPreferences()).thenReturn(
                UserPreferences.builder().modelTier(TIER_DEEP).build());

        Skill skill = Skill.builder().name("generic").description(CONTENT_TEST)
                .content("content").available(true).build();

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        assertEquals(TIER_DEEP, ctx.getModelTier());
    }

    @Test
    void resolveTier_nullFallbackWhenNoPrefsNoSkill() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(userPreferencesService.getPreferences()).thenReturn(
                UserPreferences.builder().build());

        AgentContext ctx = createContext();
        system.process(ctx);

        assertNull(ctx.getModelTier());
    }

    @Test
    void resolveTier_forceWithNullTierDoesNotForce() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(userPreferencesService.getPreferences()).thenReturn(
                UserPreferences.builder().tierForce(true).build());

        Skill skill = Skill.builder().name("coder").description("code")
                .content("instructions").modelTier(TIER_CODING).available(true).build();

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        // force=true but userTier=null → falls through to skill tier
        assertEquals(TIER_CODING, ctx.getModelTier());
    }

    // ===== Tier awareness instruction in prompt =====

    @Test
    void tierAwarenessInstructionIncludedWhenSkillHasTier() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(userPreferencesService.getPreferences()).thenReturn(
                UserPreferences.builder().build());

        Skill skill = Skill.builder().name(SKILL_CODE_REVIEW).description("Review code")
                .content("Review the code.").modelTier(TIER_CODING).available(true).build();

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        assertTrue(ctx.getSystemPrompt().contains("# Model Tier"));
        assertTrue(ctx.getSystemPrompt().contains(SKILL_CODE_REVIEW));
        assertTrue(ctx.getSystemPrompt().contains(TIER_CODING));
    }

    @Test
    void tierAwarenessInstructionOmittedWhenForced() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(userPreferencesService.getPreferences()).thenReturn(
                UserPreferences.builder().modelTier(TIER_SMART).tierForce(true).build());

        Skill skill = Skill.builder().name(SKILL_CODE_REVIEW).description("Review code")
                .content("Review the code.").modelTier(TIER_CODING).available(true).build();

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        assertFalse(ctx.getSystemPrompt().contains("# Model Tier"));
    }

    @Test
    void tierAwarenessInstructionOmittedWhenSkillHasNoTier() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(userPreferencesService.getPreferences()).thenReturn(
                UserPreferences.builder().build());

        Skill skill = Skill.builder().name("generic").description("Generic")
                .content("Do stuff.").available(true).build();

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        assertFalse(ctx.getSystemPrompt().contains("# Model Tier"));
    }

    // ===== RAG error paths =====

    @Test
    void ragQueryExceptionDoesNotBreakPipeline() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(ragPort.isAvailable()).thenReturn(true);
        when(runtimeConfigService.getRagQueryMode()).thenReturn("hybrid");
        when(ragPort.query(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("RAG timeout")));

        AgentContext ctx = createContext();
        system.process(ctx);

        assertNotNull(ctx.getSystemPrompt());
        assertFalse(ctx.getSystemPrompt().contains("# Relevant Memory"));
    }

    @Test
    void ragSkippedWhenBlankResult() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(ragPort.isAvailable()).thenReturn(true);
        when(runtimeConfigService.getRagQueryMode()).thenReturn("hybrid");
        when(ragPort.query(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture("   "));

        AgentContext ctx = createContext();
        system.process(ctx);

        assertFalse(ctx.getSystemPrompt().contains("# Relevant Memory"));
    }

    @Test
    void ragSkippedWhenNoUserMessages() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(ragPort.isAvailable()).thenReturn(true);

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("system").content("System init").timestamp(Instant.now()).build())))
                .build();
        system.process(ctx);

        verify(ragPort, never()).query(anyString(), anyString());
    }

    @Test
    void ragSkippedWhenEmptyMessages() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(ragPort.isAvailable()).thenReturn(true);

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>())
                .build();
        system.process(ctx);

        verify(ragPort, never()).query(anyString(), anyString());
    }

    // ===== Skill template variable rendering =====

    @Test
    void rendersSkillContentWithTemplateVariables() {
        when(promptSectionService.isEnabled()).thenReturn(false);

        Skill skill = Skill.builder()
                .name("greeter")
                .description("Greet user")
                .content("Hello {{USER_NAME}}, welcome to {{PRODUCT}}!")
                .resolvedVariables(Map.of("USER_NAME", "Alice", "PRODUCT", "GolemCore"))
                .available(true)
                .build();

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.contains("Hello Alice, welcome to GolemCore!"));
        assertFalse(prompt.contains("{{USER_NAME}}"));
    }
}
