package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
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
import me.golemcore.bot.domain.context.layer.WebhookResponseSchemaLayer;
import me.golemcore.bot.domain.context.layer.WorkspaceInstructionsLayer;
import me.golemcore.bot.domain.context.resolution.SkillResolver;
import me.golemcore.bot.domain.context.resolution.TierResolver;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillTransitionRequest;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SkillTemplateEngine;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.WorkspaceInstructionService;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.RagPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    private static final String ATTR_ACTIVE_SKILL_SOURCE = "skill.active.source";
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
    private AutoModeService autoModeService;
    private DelayedActionPolicyService delayedActionPolicyService;
    private PlanService planService;
    private PromptSectionService promptSectionService;
    private RuntimeConfigService runtimeConfigService;
    private ModelSelectionService modelSelectionService;
    private UserPreferencesService userPreferencesService;
    private WorkspaceInstructionService workspaceInstructionService;
    private SkillResolver skillResolver;
    private TierResolver tierResolver;
    private ContextBuildingSystem system;

    @BeforeEach
    void setUp() {
        memoryComponent = mock(MemoryComponent.class);
        skillComponent = mock(SkillComponent.class);
        templateEngine = new SkillTemplateEngine();
        mcpPort = mock(McpPort.class);
        toolCallExecutionService = mock(ToolCallExecutionService.class);
        ragPort = mock(RagPort.class);
        autoModeService = mock(AutoModeService.class);
        delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        planService = mock(PlanService.class);
        promptSectionService = mock(PromptSectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        userPreferencesService = mock(UserPreferencesService.class);
        workspaceInstructionService = mock(WorkspaceInstructionService.class);
        when(runtimeConfigService.getAutoModelTier()).thenReturn(TIER_SMART);
        when(runtimeConfigService.getAutoReflectionModelTier()).thenReturn(TIER_DEEP);
        when(modelSelectionService.resolveForTier(any()))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-smart", "high"));

        when(memoryComponent.buildMemoryPack(any())).thenReturn(MemoryPack.builder()
                .renderedContext("")
                .build());
        when(skillComponent.getSkillsSummary()).thenReturn("");
        when(toolCallExecutionService.listTools()).thenReturn(List.of());
        when(ragPort.isAvailable()).thenReturn(false);
        when(workspaceInstructionService.getWorkspaceInstructionsContext()).thenReturn("");
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().build());

        system = buildSystem();
    }

    private ContextBuildingSystem buildSystem() {
        skillResolver = new SkillResolver(skillComponent);
        tierResolver = new TierResolver(userPreferencesService, modelSelectionService, runtimeConfigService,
                skillComponent);
        PromptComposer promptComposer = new PromptComposer();

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
                new HiveLayer(),
                new WebhookResponseSchemaLayer());

        ContextAssembler contextAssembler = new ContextAssembler(skillResolver, tierResolver, layers, promptComposer);
        return new ContextBuildingSystem(contextAssembler, null, null, null);
    }

    private AgentContext createContext() {
        return AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("Hello").timestamp(Instant.now()).build())))
                .build();
    }

    @Test
    void buildSystemPrompt_withWebhookResponseSchema() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("hook-1").channelType("webhook").build())
                .messages(new ArrayList<>(List.of(Message.builder()
                        .role("user")
                        .content("Answer in JSON")
                        .metadata(Map.of(ContextAttributes.WEBHOOK_RESPONSE_JSON_SCHEMA_TEXT, """
                                {
                                  "$schema" : "https://json-schema.org/draft/2020-12/schema",
                                  "type" : "object",
                                  "required" : [ "version", "response" ]
                                }
                                """))
                        .timestamp(Instant.now())
                        .build())))
                .build();

        system.process(context);

        assertTrue(context.getSystemPrompt().contains("Webhook Response JSON Contract"));
        assertTrue(context.getSystemPrompt().contains("Return only the JSON payload"));
        assertTrue(context.getSystemPrompt().contains("\"version\""));
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
    void process_setsImplicitDefaultTierTraceMetadataWhenNoTierResolvedExplicitly() {
        when(runtimeConfigService.getAutoModelTier()).thenReturn(null);
        when(userPreferencesService.getPreferences()).thenReturn(UserPreferences.builder().build());

        AgentContext ctx = createContext();
        system.process(ctx);

        assertEquals("implicit_default", ctx.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
        assertEquals("gpt-5-smart", ctx.getAttribute(ContextAttributes.MODEL_TIER_MODEL_ID));
        assertEquals("high", ctx.getAttribute(ContextAttributes.MODEL_TIER_REASONING));
    }

    @Test
    void process_setsActiveSkillNameAndSkillTierTraceMetadata() {
        AgentContext ctx = createContext();
        ctx.setActiveSkill(Skill.builder().name(SKILL_PROCESSING).modelTier(TIER_CODING).build());
        when(modelSelectionService.resolveForTier(TIER_CODING))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-coding", "medium"));

        system.process(ctx);

        assertEquals(SKILL_PROCESSING, ctx.getAttribute(ContextAttributes.ACTIVE_SKILL_NAME));
        assertEquals("skill", ctx.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
        assertEquals("gpt-5-coding", ctx.getAttribute(ContextAttributes.MODEL_TIER_MODEL_ID));
        assertEquals("medium", ctx.getAttribute(ContextAttributes.MODEL_TIER_REASONING));
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
    void buildSystemPrompt_includesWaitingAndFollowUpsSection() {
        when(promptSectionService.isEnabled()).thenReturn(true);
        when(promptSectionService.buildTemplateVariables(any())).thenReturn(Map.of());
        when(promptSectionService.getEnabledSections()).thenReturn(List.of(
                PromptSection.builder().name(SECTION_IDENTITY).content("Identity").order(10).build(),
                PromptSection.builder()
                        .name("waiting-and-followups")
                        .content("Do not ask the user to come back manually. Confirm the next local check time.")
                        .order(25)
                        .build()));
        when(promptSectionService.renderSection(any(), any()))
                .thenAnswer(inv -> ((PromptSection) inv.getArgument(0)).getContent());

        AgentContext ctx = createContext();
        system.process(ctx);

        assertTrue(ctx.getSystemPrompt().contains("Do not ask the user to come back manually."));
        assertTrue(ctx.getSystemPrompt().contains("Confirm the next local check time."));
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
        when(memoryComponent.buildMemoryPack(any())).thenReturn(MemoryPack.builder()
                .renderedContext("User prefers concise answers.")
                .build());

        ToolComponent tool = mock(ToolComponent.class);
        when(tool.isEnabled()).thenReturn(true);
        when(tool.getDefinition()).thenReturn(ToolDefinition.builder()
                .name("test_tool")
                .description("A test tool")
                .inputSchema(Map.of("type", "object"))
                .build());
        when(toolCallExecutionService.listTools()).thenReturn(List.of(tool));

        system = buildSystem();

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

    @Test
    void storesMemoryPackDiagnosticsInContextAttributes() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(memoryComponent.buildMemoryPack(any())).thenReturn(MemoryPack.builder()
                .renderedContext("## Semantic Memory\n- [PROJECT_FACT] Uses Spring")
                .diagnostics(Map.of("selectedCount", 1))
                .build());

        AgentContext ctx = createContext();
        system.process(ctx);

        Object diagnostics = ctx.getAttribute(ContextAttributes.MEMORY_PACK_DIAGNOSTICS);
        assertNotNull(diagnostics);
        assertTrue(diagnostics instanceof Map<?, ?>);
        assertTrue(ctx.getMemoryContext().contains("Semantic Memory"));
    }

    @Test
    void buildsMemoryQueryWithSessionScope() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(memoryComponent.buildMemoryPack(any())).thenReturn(MemoryPack.builder()
                .renderedContext("")
                .build());

        AgentSession session = AgentSession.builder()
                .id("web:conv12345")
                .channelType("web")
                .chatId("conv12345")
                .metadata(new HashMap<>(Map.of(ContextAttributes.CONVERSATION_KEY, "conv12345")))
                .build();
        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("Hello").timestamp(Instant.now()).build())))
                .build();

        system.process(ctx);

        verify(memoryComponent).buildMemoryPack(argThat((MemoryQuery query) -> query != null
                && "session:web:conv12345".equals(query.getScope())));
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

    @Test
    void injectsStrictSkillActivationInstructionWhenSkillsAreAvailable() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(skillComponent.getSkillsSummary()).thenReturn("- workflow: Handle multi-step workflows");

        AgentContext ctx = createContext();
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.contains("If one of the available skills clearly matches the user's request"));
        assertTrue(prompt.contains("call the skill_transition tool before doing the work"));
    }

    @Test
    void restoresActiveSkillFromSessionMetadataAndPersistsTierMetadata() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        Skill restoredSkill = Skill.builder()
                .name(SKILL_PROCESSING)
                .content("Process the task carefully")
                .modelTier(TIER_CODING)
                .available(true)
                .build();
        when(skillComponent.findByName(SKILL_PROCESSING)).thenReturn(Optional.of(restoredSkill));
        when(modelSelectionService.resolveForTier(TIER_CODING))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-coding", "medium"));

        AgentContext ctx = createContext();
        ctx.getSession().getMetadata().put(ContextAttributes.ACTIVE_SKILL_NAME, SKILL_PROCESSING);

        system.process(ctx);

        assertNotNull(ctx.getActiveSkill());
        assertEquals(SKILL_PROCESSING, ctx.getActiveSkill().getName());
        assertEquals(SKILL_PROCESSING, ctx.getAttribute(ContextAttributes.ACTIVE_SKILL_NAME));
        assertEquals("session_state", ctx.getAttribute(ATTR_ACTIVE_SKILL_SOURCE));
        assertEquals(SKILL_PROCESSING, ctx.getSession().getMetadata().get(ContextAttributes.ACTIVE_SKILL_NAME));
        assertEquals("skill", ctx.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
        assertEquals("gpt-5-coding", ctx.getAttribute(ContextAttributes.MODEL_TIER_MODEL_ID));
        assertTrue(ctx.getSystemPrompt().contains("# Active Skill: " + SKILL_PROCESSING));
    }

    @Test
    void restoresActiveSkillFromContextAttributesUsingMessageMetadataSource() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        Skill restoredSkill = Skill.builder()
                .name(SKILL_PROCESSING)
                .content("Process the task carefully")
                .available(true)
                .build();
        when(skillComponent.findByName(SKILL_PROCESSING)).thenReturn(Optional.of(restoredSkill));

        AgentContext ctx = createContext();
        ctx.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, SKILL_PROCESSING);

        system.process(ctx);

        assertNotNull(ctx.getActiveSkill());
        assertEquals(SKILL_PROCESSING, ctx.getActiveSkill().getName());
        assertEquals("message_metadata", ctx.getAttribute(ATTR_ACTIVE_SKILL_SOURCE));
        assertEquals(SKILL_PROCESSING, ctx.getSession().getMetadata().get(ContextAttributes.ACTIVE_SKILL_NAME));
    }

    @Test
    void restoresActiveSkillFromContextAttributesPreservingExistingSource() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        Skill restoredSkill = Skill.builder()
                .name(SKILL_PROCESSING)
                .content("Process the task carefully")
                .available(true)
                .build();
        when(skillComponent.findByName(SKILL_PROCESSING)).thenReturn(Optional.of(restoredSkill));

        AgentContext ctx = createContext();
        ctx.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, SKILL_PROCESSING);
        ctx.setAttribute(ContextAttributes.ACTIVE_SKILL_SOURCE, "message_metadata_override");

        system.process(ctx);

        assertNotNull(ctx.getActiveSkill());
        assertEquals("message_metadata_override", ctx.getAttribute(ATTR_ACTIVE_SKILL_SOURCE));
    }

    @Test
    void persistsPreselectedActiveSkillIntoSessionMetadata() {
        when(promptSectionService.isEnabled()).thenReturn(false);

        AgentContext ctx = createContext();
        ctx.getSession().setMetadata(new HashMap<>());
        ctx.setActiveSkill(Skill.builder()
                .name(SKILL_PROCESSING)
                .content("Process the task carefully")
                .available(true)
                .build());

        system.process(ctx);

        assertEquals(SKILL_PROCESSING, ctx.getSession().getMetadata().get(ContextAttributes.ACTIVE_SKILL_NAME));
    }

    @Test
    void createsSessionMetadataWhenPersistingPreselectedActiveSkill() {
        when(promptSectionService.isEnabled()).thenReturn(false);

        AgentContext ctx = createContext();
        ctx.getSession().setMetadata(null);
        ctx.setActiveSkill(Skill.builder()
                .name(SKILL_PROCESSING)
                .content("Process the task carefully")
                .available(true)
                .build());

        system.process(ctx);

        assertNotNull(ctx.getSession().getMetadata());
        assertEquals(SKILL_PROCESSING, ctx.getSession().getMetadata().get(ContextAttributes.ACTIVE_SKILL_NAME));
    }

    @Test
    void doesNotPersistPreselectedActiveSkillWhenNameIsBlank() {
        when(promptSectionService.isEnabled()).thenReturn(false);

        AgentContext ctx = createContext();
        ctx.setActiveSkill(Skill.builder()
                .name("   ")
                .content("Process the task carefully")
                .available(true)
                .build());

        system.process(ctx);

        assertTrue(ctx.getSession().getMetadata() == null
                || !ctx.getSession().getMetadata().containsKey(ContextAttributes.ACTIVE_SKILL_NAME));
    }

    @Test
    void clearsPersistedActiveSkillWhenSessionMetadataSkillIsMissing() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(skillComponent.findByName("missing-skill")).thenReturn(Optional.empty());

        AgentContext ctx = createContext();
        ctx.getSession().setMetadata(new HashMap<>(Map.of(
                ContextAttributes.ACTIVE_SKILL_NAME, "missing-skill")));

        system.process(ctx);

        assertNull(ctx.getActiveSkill());
        assertFalse(ctx.getSession().getMetadata().containsKey(ContextAttributes.ACTIVE_SKILL_NAME));
    }

    @Test
    void helperMethodsHandleInvalidStickySkillInputs() throws Exception {
        AgentContext ctx = createContext();
        ctx.getSession().setMetadata(null);

        assertEquals(
                Boolean.FALSE,
                ReflectionTestUtils.invokeMethod(skillResolver, "applyActiveSkillByName", ctx, " ",
                        "message_metadata"));

        ReflectionTestUtils.invokeMethod(skillResolver, "clearPersistedActiveSkill", ctx);
        assertTrue(ctx.getSession().getMetadata() == null || ctx.getSession().getMetadata().isEmpty());

        assertNull(ReflectionTestUtils.invokeMethod(skillResolver, "formatActiveSkillSource",
                (SkillTransitionRequest) null));
    }

    @Test
    void clearsPersistedActiveSkillWhenSessionMetadataSkillIsUnavailable() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        Skill unavailableSkill = Skill.builder()
                .name(SKILL_PROCESSING)
                .content("Process the task carefully")
                .available(false)
                .build();
        when(skillComponent.findByName(SKILL_PROCESSING)).thenReturn(Optional.of(unavailableSkill));

        AgentContext ctx = createContext();
        ctx.getSession().setMetadata(new HashMap<>(Map.of(
                ContextAttributes.ACTIVE_SKILL_NAME, SKILL_PROCESSING)));

        system.process(ctx);

        assertNull(ctx.getActiveSkill());
        assertFalse(ctx.getSession().getMetadata().containsKey(ContextAttributes.ACTIVE_SKILL_NAME));
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
        when(ragPort.query(anyString()))
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
        verify(ragPort, never()).query(anyString());
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

        verify(toolCallExecutionService, never()).registerTool(any());
        assertTrue(ctx.getAvailableTools().contains(mcpTool));
        assertTrue(ctx.getSystemPrompt().contains("github_search"));
        Object scopedTools = ctx.getAttribute(ContextAttributes.CONTEXT_SCOPED_TOOLS);
        assertTrue(scopedTools instanceof Map<?, ?>);
        assertEquals(mcpAdapter, ((Map<?, ?>) scopedTools).get("github_search"));
    }

    @Test
    void shouldDeduplicateGlobalToolDefinitionWhenActiveMcpToolUsesSameName() {
        when(promptSectionService.isEnabled()).thenReturn(false);

        ToolDefinition globalToolDefinition = ToolDefinition.builder()
                .name("github_search")
                .description("Global search")
                .inputSchema(Map.of("type", "object"))
                .build();
        ToolComponent globalTool = mock(ToolComponent.class);
        when(globalTool.isEnabled()).thenReturn(true);
        when(globalTool.getDefinition()).thenReturn(globalToolDefinition);
        when(toolCallExecutionService.listTools()).thenReturn(List.of(globalTool));

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
                .description("Search GitHub via MCP")
                .build();
        when(mcpPort.getOrStartClient(skill)).thenReturn(List.of(mcpTool));

        ToolComponent mcpAdapter = mock(ToolComponent.class);
        when(mcpPort.createToolAdapter(eq("github"), eq(mcpTool))).thenReturn(mcpAdapter);

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        assertEquals(1, ctx.getAvailableTools().size());
        assertEquals("Search GitHub via MCP", ctx.getAvailableTools().get(0).getDescription());
        Object scopedTools = ctx.getAttribute(ContextAttributes.CONTEXT_SCOPED_TOOLS);
        assertTrue(scopedTools instanceof Map<?, ?>);
        assertEquals(mcpAdapter, ((Map<?, ?>) scopedTools).get("github_search"));
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
        ctx.setSkillTransitionRequest(SkillTransitionRequest.explicit(SKILL_PROCESSING));
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

    @Test
    void shouldPreferSkillReflectionTierWhenTaskTierIsNotPriority() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(autoModeService.buildAutoContext()).thenReturn("# Goals\n- Recover");

        Skill skill = Skill.builder()
                .name("recovery-skill")
                .description("Recovery")
                .content("Recover")
                .reflectionTier(TIER_DEEP)
                .available(true)
                .build();

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("Auto task")
                                .timestamp(Instant.now())
                                .metadata(new HashMap<>(Map.of(
                                        ContextAttributes.AUTO_MODE, true,
                                        ContextAttributes.AUTO_REFLECTION_ACTIVE, true,
                                        ContextAttributes.AUTO_REFLECTION_TIER, TIER_SMART,
                                        ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, false)))
                                .build())))
                .build();
        ctx.setActiveSkill(skill);

        system.process(ctx);

        assertEquals(TIER_DEEP, ctx.getModelTier());
        assertTrue(ctx.getSystemPrompt().contains("# Auto Reflection Mode"));
    }

    @Test
    void shouldPreferTaskReflectionTierWhenMarkedPriority() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(autoModeService.buildAutoContext()).thenReturn("# Goals\n- Recover");

        Skill skill = Skill.builder()
                .name("recovery-skill")
                .description("Recovery")
                .content("Recover")
                .reflectionTier(TIER_DEEP)
                .available(true)
                .build();

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("Auto task")
                                .timestamp(Instant.now())
                                .metadata(new HashMap<>(Map.of(
                                        ContextAttributes.AUTO_MODE, true,
                                        ContextAttributes.AUTO_REFLECTION_ACTIVE, true,
                                        ContextAttributes.AUTO_REFLECTION_TIER, TIER_SMART,
                                        ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, true)))
                                .build())))
                .build();
        ctx.setActiveSkill(skill);

        system.process(ctx);

        assertEquals(TIER_SMART, ctx.getModelTier());
    }

    @Test
    void shouldPreferLastUsedSkillReflectionTierFromMetadata() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(autoModeService.buildAutoContext()).thenReturn("# Goals\n- Recover");

        Skill skill = Skill.builder()
                .name("reviewer-skill")
                .description("Recovery")
                .content("Recover")
                .reflectionTier(TIER_DEEP)
                .available(true)
                .build();
        when(skillComponent.findByName("reviewer-skill")).thenReturn(Optional.of(skill));

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("Auto task")
                                .timestamp(Instant.now())
                                .metadata(new HashMap<>(Map.of(
                                        ContextAttributes.AUTO_MODE, true,
                                        ContextAttributes.AUTO_REFLECTION_ACTIVE, true,
                                        ContextAttributes.AUTO_REFLECTION_TIER, TIER_SMART,
                                        ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, false,
                                        ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "reviewer-skill")))
                                .build())))
                .build();

        system.process(ctx);

        assertEquals(TIER_DEEP, ctx.getModelTier());
    }

    @Test
    void shouldUseConfiguredReflectionOverrideWhenSkillHasNoReflectionTier() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(autoModeService.buildAutoContext()).thenReturn("# Goals\n- Recover");
        when(modelSelectionService.resolveForTier(TIER_SMART))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-smart", "high"));

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("Auto task")
                                .timestamp(Instant.now())
                                .metadata(new HashMap<>(Map.of(
                                        ContextAttributes.AUTO_MODE, true,
                                        ContextAttributes.AUTO_REFLECTION_ACTIVE, true,
                                        ContextAttributes.AUTO_REFLECTION_TIER, TIER_SMART,
                                        ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, false)))
                                .build())))
                .build();

        system.process(ctx);

        assertEquals(TIER_SMART, ctx.getModelTier());
        assertEquals("reflection_override", ctx.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
        assertEquals("gpt-5-smart", ctx.getAttribute(ContextAttributes.MODEL_TIER_MODEL_ID));
    }

    @Test
    void shouldFallbackToRuntimeReflectionTierWhenNoTaskOrSkillOverrideExists() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(autoModeService.buildAutoContext()).thenReturn("# Goals\n- Recover");
        when(runtimeConfigService.getAutoReflectionModelTier()).thenReturn(TIER_DEEP);
        when(modelSelectionService.resolveForTier(TIER_DEEP))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-deep", "max"));

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("Auto task")
                                .timestamp(Instant.now())
                                .metadata(new HashMap<>(Map.of(
                                        ContextAttributes.AUTO_MODE, true,
                                        ContextAttributes.AUTO_REFLECTION_ACTIVE, true)))
                                .build())))
                .build();

        system.process(ctx);

        assertEquals(TIER_DEEP, ctx.getModelTier());
        assertEquals("runtime_reflection", ctx.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
        assertEquals("gpt-5-deep", ctx.getAttribute(ContextAttributes.MODEL_TIER_MODEL_ID));
        assertEquals("max", ctx.getAttribute(ContextAttributes.MODEL_TIER_REASONING));
    }

    @Test
    void shouldFallbackToUserTierDuringReflectionWhenNothingElseIsConfigured() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(autoModeService.buildAutoContext()).thenReturn("# Goals\n- Recover");
        when(runtimeConfigService.getAutoReflectionModelTier()).thenReturn(null);
        when(userPreferencesService.getPreferences()).thenReturn(UserPreferences.builder()
                .modelTier(TIER_SMART)
                .build());

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("Auto task")
                                .timestamp(Instant.now())
                                .metadata(new HashMap<>(Map.of(
                                        ContextAttributes.AUTO_MODE, true,
                                        ContextAttributes.AUTO_REFLECTION_ACTIVE, true)))
                                .build())))
                .build();

        system.process(ctx);

        assertEquals(TIER_SMART, ctx.getModelTier());
        assertEquals("user_pref", ctx.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldClearResolvedTierMetadataWhenReflectionResolutionReturnsBlankReasoning() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(autoModeService.buildAutoContext()).thenReturn("# Goals\n- Recover");
        when(modelSelectionService.resolveForTier(TIER_SMART))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-smart", ""));

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("Auto task")
                                .timestamp(Instant.now())
                                .metadata(new HashMap<>(Map.of(
                                        ContextAttributes.AUTO_MODE, true,
                                        ContextAttributes.AUTO_REFLECTION_ACTIVE, true,
                                        ContextAttributes.AUTO_REFLECTION_TIER, TIER_SMART,
                                        ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, false)))
                                .build())))
                .build();
        ctx.setAttribute(ContextAttributes.MODEL_TIER_REASONING, "stale-reasoning");

        system.process(ctx);

        assertEquals("gpt-5-smart", ctx.getAttribute(ContextAttributes.MODEL_TIER_MODEL_ID));
        assertNull(ctx.getAttribute(ContextAttributes.MODEL_TIER_REASONING));
    }

    @Test
    void shouldClearResolvedTierMetadataWhenReflectionResolutionFails() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(autoModeService.buildAutoContext()).thenReturn("# Goals\n- Recover");
        when(modelSelectionService.resolveForTier(TIER_SMART))
                .thenThrow(new IllegalStateException("missing tier mapping"));

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("Auto task")
                                .timestamp(Instant.now())
                                .metadata(new HashMap<>(Map.of(
                                        ContextAttributes.AUTO_MODE, true,
                                        ContextAttributes.AUTO_REFLECTION_ACTIVE, true,
                                        ContextAttributes.AUTO_REFLECTION_TIER, TIER_SMART,
                                        ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, false)))
                                .build())))
                .build();
        ctx.setAttribute(ContextAttributes.MODEL_TIER_MODEL_ID, "stale-model");
        ctx.setAttribute(ContextAttributes.MODEL_TIER_REASONING, "stale-reasoning");

        system.process(ctx);

        assertNull(ctx.getAttribute(ContextAttributes.MODEL_TIER_MODEL_ID));
        assertNull(ctx.getAttribute(ContextAttributes.MODEL_TIER_REASONING));
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

        // force=true but userTier=null -> falls through to skill tier
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
        when(ragPort.query(anyString()))
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
        when(ragPort.query(anyString()))
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

        verify(ragPort, never()).query(anyString());
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

        verify(ragPort, never()).query(anyString());
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

    @Test
    void shouldUseLastVisibleUserMessageForMemoryAndRagQueries() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        when(ragPort.isAvailable()).thenReturn(true);
        when(ragPort.query(anyString())).thenReturn(CompletableFuture.completedFuture("recalled context"));

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("Visible user request")
                                .timestamp(Instant.now()).build(),
                        Message.builder().role("user").content("Internal retry prompt")
                                .metadata(Map.of(ContextAttributes.MESSAGE_INTERNAL, true))
                                .timestamp(Instant.now()).build())))
                .build();

        system.process(ctx);

        verify(memoryComponent).buildMemoryPack(argThat((MemoryQuery query) -> query != null
                && "Visible user request".equals(query.getQueryText())));
        verify(ragPort).query("Visible user request");
    }
}
