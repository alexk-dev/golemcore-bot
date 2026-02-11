package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.SkillTemplateEngine;
import me.golemcore.bot.domain.service.UserPreferencesService;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextBuildingSystemPromptTest {

    private static final String SECTION_IDENTITY = "identity";
    private static final String SKILL_PROCESSING = "processing";

    private MemoryComponent memoryComponent;
    private SkillComponent skillComponent;
    private SkillTemplateEngine templateEngine;
    private McpPort mcpPort;
    private ToolExecutionSystem toolExecutionSystem;
    private RagPort ragPort;
    private BotProperties properties;
    private AutoModeService autoModeService;
    private PlanService planService;
    private PromptSectionService promptSectionService;
    private UserPreferencesService userPreferencesService;
    private ContextBuildingSystem system;

    @BeforeEach
    void setUp() {
        memoryComponent = mock(MemoryComponent.class);
        skillComponent = mock(SkillComponent.class);
        templateEngine = new SkillTemplateEngine();
        mcpPort = mock(McpPort.class);
        toolExecutionSystem = mock(ToolExecutionSystem.class);
        ragPort = mock(RagPort.class);
        properties = new BotProperties();
        autoModeService = mock(AutoModeService.class);
        planService = mock(PlanService.class);
        promptSectionService = mock(PromptSectionService.class);
        userPreferencesService = mock(UserPreferencesService.class);

        when(memoryComponent.getMemoryContext()).thenReturn("");
        when(skillComponent.getSkillsSummary()).thenReturn("");
        when(ragPort.isAvailable()).thenReturn(false);
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().build());

        system = new ContextBuildingSystem(
                memoryComponent,
                skillComponent,
                List.of(),
                templateEngine,
                mcpPort,
                toolExecutionSystem,
                ragPort,
                properties,
                autoModeService,
                planService,
                promptSectionService,
                userPreferencesService);
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
                toolExecutionSystem,
                ragPort,
                properties,
                autoModeService,
                planService,
                promptSectionService,
                userPreferencesService);

        AgentContext ctx = createContext();
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.contains("Bot identity"));
        assertTrue(prompt.contains("# Memory"));
        assertTrue(prompt.contains("User prefers concise answers."));
        assertTrue(prompt.contains("# Available Tools"));
        assertTrue(prompt.contains("test_tool"));
    }

    // ===== Active skill injection =====

    @Test
    void injectsActiveSkillContent() {
        when(promptSectionService.isEnabled()).thenReturn(false);

        Skill skill = Skill.builder()
                .name("code-review")
                .description("Review code")
                .content("You are a code reviewer. Analyze code carefully.")
                .available(true)
                .build();

        AgentContext ctx = createContext();
        ctx.setActiveSkill(skill);
        system.process(ctx);

        String prompt = ctx.getSystemPrompt();
        assertTrue(prompt.contains("# Active Skill: code-review"));
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

        verify(toolExecutionSystem).registerTool(mcpAdapter);
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
        when(skillComponent.findByName(SKILL_PROCESSING)).thenReturn(java.util.Optional.of(targetSkill));

        AgentContext ctx = createContext();
        ctx.setAttribute("skill.transition.target", SKILL_PROCESSING);
        system.process(ctx);

        assertEquals(targetSkill, ctx.getActiveSkill());
        assertNull(ctx.getAttribute("skill.transition.target")); // cleared after transition
        assertTrue(ctx.getSystemPrompt().contains("# Active Skill: processing"));
    }

    // ===== Auto mode =====

    @Test
    void setsAutoModeTierForAutoMessages() {
        when(promptSectionService.isEnabled()).thenReturn(false);
        properties.getAuto().setModelTier("smart");

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

        assertEquals("smart", ctx.getModelTier());
        assertTrue(ctx.getSystemPrompt().contains("# Goals"));
    }
}
