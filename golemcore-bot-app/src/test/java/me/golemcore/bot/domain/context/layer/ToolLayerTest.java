package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.port.outbound.McpPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolLayerTest {

    private ToolCallExecutionService toolCallExecutionService;
    private McpPort mcpPort;
    private PlanService planService;
    private DelayedActionPolicyService delayedActionPolicyService;
    private ToolLayer layer;

    @BeforeEach
    void setUp() {
        toolCallExecutionService = mock(ToolCallExecutionService.class);
        mcpPort = mock(McpPort.class);
        planService = mock(PlanService.class);
        delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        layer = new ToolLayer(toolCallExecutionService, mcpPort, planService, delayedActionPolicyService);
    }

    @Test
    void shouldAlwaysApply() {
        assertTrue(layer.appliesTo(AgentContext.builder().build()));
    }

    @Test
    void shouldCollectEnabledToolDefinitions() {
        ToolComponent tool = mock(ToolComponent.class);
        when(tool.isEnabled()).thenReturn(true);
        when(tool.getToolName()).thenReturn("shell");
        when(tool.getDefinition()).thenReturn(
                ToolDefinition.builder().name("shell").description("Run shell commands").build());

        when(toolCallExecutionService.listTools()).thenReturn(List.of(tool));

        AgentContext context = AgentContext.builder().build();
        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("# Available Tools"));
        assertTrue(result.getContent().contains("**shell**"));
        assertEquals(1, context.getAvailableTools().size());
    }

    @Test
    void shouldIncludeShellPolicyWhenShellToolIsAdvertised() {
        ToolComponent tool = mock(ToolComponent.class);
        when(tool.isEnabled()).thenReturn(true);
        when(tool.getToolName()).thenReturn(ToolNames.SHELL);
        when(tool.getDefinition()).thenReturn(
                ToolDefinition.builder().name(ToolNames.SHELL).description("Run shell commands").build());

        when(toolCallExecutionService.listTools()).thenReturn(List.of(tool));

        AgentContext context = AgentContext.builder().build();
        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("## Shell Tool Policy"));
        assertTrue(result.getContent().contains("command -v"));
        assertTrue(result.getContent().contains("same missing command twice"));
    }

    @Test
    void shouldNotIncludeShellPolicyWhenShellToolIsNotAdvertised() {
        ToolComponent tool = mock(ToolComponent.class);
        when(tool.isEnabled()).thenReturn(true);
        when(tool.getToolName()).thenReturn("filesystem");
        when(tool.getDefinition()).thenReturn(
                ToolDefinition.builder().name("filesystem").description("Work with files").build());

        when(toolCallExecutionService.listTools()).thenReturn(List.of(tool));

        AgentContext context = AgentContext.builder().build();
        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertFalse(result.getContent().contains("## Shell Tool Policy"));
        assertFalse(result.getContent().contains("command -v"));
    }

    @Test
    void shouldFilterDisabledTools() {
        ToolComponent enabled = mock(ToolComponent.class);
        when(enabled.isEnabled()).thenReturn(true);
        when(enabled.getToolName()).thenReturn("shell");
        when(enabled.getDefinition()).thenReturn(
                ToolDefinition.builder().name("shell").description("Shell").build());

        ToolComponent disabled = mock(ToolComponent.class);
        when(disabled.isEnabled()).thenReturn(false);

        when(toolCallExecutionService.listTools()).thenReturn(List.of(enabled, disabled));

        AgentContext context = AgentContext.builder().build();
        layer.assemble(context);

        assertEquals(1, context.getAvailableTools().size());
    }

    @Test
    void shouldHideMemoryToolWhenMemoryPresetIsDisabled() {
        ToolComponent memoryTool = mock(ToolComponent.class);
        when(memoryTool.isEnabled()).thenReturn(true);
        when(memoryTool.getToolName()).thenReturn(ToolNames.MEMORY);
        when(memoryTool.getDefinition()).thenReturn(
                ToolDefinition.builder().name(ToolNames.MEMORY).description("Memory").build());

        ToolComponent shellTool = mock(ToolComponent.class);
        when(shellTool.isEnabled()).thenReturn(true);
        when(shellTool.getToolName()).thenReturn("shell");
        when(shellTool.getDefinition()).thenReturn(
                ToolDefinition.builder().name("shell").description("Shell").build());

        when(toolCallExecutionService.listTools()).thenReturn(List.of(memoryTool, shellTool));

        AgentContext context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.MEMORY_PRESET_ID, "disabled");
        layer.assemble(context);

        assertEquals(1, context.getAvailableTools().size());
        assertEquals("shell", context.getAvailableTools().get(0).getName());
    }

    @ParameterizedTest
    @ValueSource(strings = { "telegram", "hive", "web" })
    void shouldAdvertiseMemoryToolForNonWebhookChatsWhenPresetIsMissing(String channelType) {
        ToolComponent memoryTool = mock(ToolComponent.class);
        when(memoryTool.isEnabled()).thenReturn(true);
        when(memoryTool.getToolName()).thenReturn(ToolNames.MEMORY);
        when(memoryTool.getDefinition()).thenReturn(
                ToolDefinition.builder().name(ToolNames.MEMORY).description("Memory").build());
        when(toolCallExecutionService.listTools()).thenReturn(List.of(memoryTool));

        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType(channelType).chatId("chat-1").build())
                .build();

        layer.assemble(context);

        assertEquals(1, context.getAvailableTools().size());
        assertEquals(ToolNames.MEMORY, context.getAvailableTools().get(0).getName());
    }

    @Test
    void shouldReturnEmptyWhenNoToolsAvailable() {
        when(toolCallExecutionService.listTools()).thenReturn(List.of());

        AgentContext context = AgentContext.builder().build();
        ContextLayerResult result = layer.assemble(context);

        assertFalse(result.hasContent());
    }

    @Test
    void shouldAdvertiseHiveSdlcToolsOnlyForHiveSessions() {
        layer = new ToolLayer(toolCallExecutionService, mcpPort, planService, delayedActionPolicyService);

        ToolComponent tool = mock(ToolComponent.class);
        when(tool.isEnabled()).thenReturn(true);
        when(tool.getToolName()).thenReturn(ToolNames.HIVE_GET_CARD);
        when(tool.getDefinition()).thenReturn(ToolDefinition.builder()
                .name(ToolNames.HIVE_GET_CARD)
                .description("Hive card read")
                .build());
        when(toolCallExecutionService.listTools()).thenReturn(List.of(tool));

        AgentContext webContext = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .build();
        layer.assemble(webContext);
        assertTrue(webContext.getAvailableTools().isEmpty());

        AgentContext hiveContext = AgentContext.builder()
                .session(AgentSession.builder().channelType("hive").chatId("thread-1").build())
                .build();
        layer.assemble(hiveContext);
        assertEquals(1, hiveContext.getAvailableTools().size());
        assertEquals(ToolNames.HIVE_GET_CARD, hiveContext.getAvailableTools().get(0).getName());
    }

    @Test
    void shouldLoadMcpToolsForActiveSkill() {
        Skill skill = Skill.builder()
                .name("github")
                .description("GitHub")
                .content("Use GitHub tools.")
                .mcpConfig(me.golemcore.bot.domain.model.McpConfig.builder()
                        .command("npx server-github").build())
                .build();

        ToolDefinition mcpTool = ToolDefinition.builder()
                .name("create_issue").description("Create GitHub issue").build();
        when(mcpPort.getOrStartClient(any())).thenReturn(List.of(mcpTool));

        ToolComponent mcpAdapter = mock(ToolComponent.class);
        when(mcpPort.createToolAdapter(any(), any())).thenReturn(mcpAdapter);

        when(toolCallExecutionService.listTools()).thenReturn(List.of());

        AgentContext context = AgentContext.builder().activeSkill(skill).build();
        layer.assemble(context);

        assertEquals(1, context.getAvailableTools().size());
        assertEquals("create_issue", context.getAvailableTools().get(0).getName());
    }

    @Test
    void shouldHaveCorrectNameAndOrder() {
        assertEquals("tool", layer.getName());
        assertEquals(50, layer.getOrder());
    }
}
