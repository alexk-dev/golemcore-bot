package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.ToolNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanModeToolRestrictionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-04-23T12:00:00Z"), ZoneOffset.UTC);
    private static final SessionIdentity SESSION = new SessionIdentity("web", "chat-1");

    private PlanService planService;
    private PlanModeToolRestrictionService restrictions;
    private AgentContext context;

    @BeforeEach
    void setUp() {
        planService = new PlanService(CLOCK);
        restrictions = new PlanModeToolRestrictionService(planService);
        context = AgentContext.builder()
                .session(AgentSession.builder()
                        .channelType("web")
                        .chatId("chat-1")
                        .build())
                .build();
    }

    @Test
    void shouldAllowEverythingWhenPlanModeIsInactive() {
        assertFalse(restrictions.denialReason(context, toolCall(ToolNames.SHELL, Map.of("command", "pwd")))
                .isPresent());
        assertTrue(restrictions.shouldAdvertiseTool(context, ToolNames.SHELL));
    }

    @Test
    void shouldDenyShellAndBashToolsInPlanMode() {
        planService.activatePlanMode(SESSION, "chat-1", null);

        assertTrue(restrictions.denialReason(context, toolCall(ToolNames.SHELL, Map.of("command", "pwd")))
                .isPresent());
        assertTrue(restrictions.denialReason(context, toolCall("bash", Map.of("command", "pwd")))
                .isPresent());
        assertFalse(restrictions.shouldAdvertiseTool(context, ToolNames.SHELL));
        assertFalse(restrictions.shouldAdvertiseTool(context, "bash"));
    }

    @Test
    void shouldAllowGoalManagementToolInPlanMode() {
        planService.activatePlanMode(SESSION, "chat-1", null);

        assertFalse(restrictions.denialReason(context,
                toolCall(ToolNames.GOAL_MANAGEMENT, Map.of("operation", "create_goal", "title", "Plan")))
                .isPresent());
        assertTrue(restrictions.shouldAdvertiseTool(context, ToolNames.GOAL_MANAGEMENT));
    }

    @Test
    void shouldDenyUnknownCustomToolsInPlanMode() {
        planService.activatePlanMode(SESSION, "chat-1", null);

        assertTrue(restrictions.denialReason(context, toolCall("custom_mcp_writer", Map.of("payload", "mutate")))
                .isPresent());
        assertFalse(restrictions.shouldAdvertiseTool(context, "custom_mcp_writer"));
    }

    @Test
    void shouldDenyTaskAndPatchFamilyToolsInPlanMode() {
        planService.activatePlanMode(SESSION, "chat-1", null);

        for (String toolName : java.util.List.of("task", "subagent", "agent", "apply_patch", "patch", "edit",
                "write")) {
            assertTrue(restrictions.denialReason(context, toolCall(toolName, Map.of("input", "mutate")))
                    .isPresent());
            assertFalse(restrictions.shouldAdvertiseTool(context, toolName));
        }
    }

    @Test
    void shouldAllowPlanExitOnlyWhilePlanModeIsActive() {
        assertFalse(restrictions.shouldAdvertiseTool(context, ToolNames.PLAN_EXIT));

        planService.activatePlanMode(SESSION, "chat-1", null);

        assertTrue(restrictions.shouldAdvertiseTool(context, ToolNames.PLAN_EXIT));
        assertFalse(restrictions.denialReason(context, toolCall(ToolNames.PLAN_EXIT, Map.of())).isPresent());
    }

    @Test
    void shouldAllowReadOnlyFilesystemOperationsInPlanMode() {
        planService.activatePlanMode(SESSION, "chat-1", null);

        assertFalse(restrictions.denialReason(context,
                toolCall(ToolNames.FILESYSTEM, Map.of("operation", "read_file", "path", "README.md")))
                .isPresent());
        assertFalse(restrictions.denialReason(context,
                toolCall(ToolNames.FILESYSTEM, Map.of("operation", "list_directory", "path", "src")))
                .isPresent());
        assertFalse(restrictions.denialReason(context,
                toolCall(ToolNames.FILESYSTEM, Map.of("operation", "file_info", "path", "pom.xml")))
                .isPresent());
        assertTrue(restrictions.shouldAdvertiseTool(context, ToolNames.FILESYSTEM));
    }

    @Test
    void shouldAllowPlanMarkdownWritesInLocalPlansDirectory() {
        planService.activatePlanMode(SESSION, "chat-1", null);
        String planFilePath = planService.getActivePlanFilePath(SESSION).orElseThrow();

        assertFalse(restrictions.denialReason(context,
                toolCall(ToolNames.FILESYSTEM, Map.of(
                        "operation", "write_file",
                        "path", planFilePath,
                        "content", "# Plan")))
                .isPresent());
    }

    @Test
    void shouldDenyWritesToOtherPlanMarkdownFiles() {
        planService.activatePlanMode(SESSION, "chat-1", null);
        String activePlanFilePath = planService.getActivePlanFilePath(SESSION).orElseThrow();
        String otherPlanFilePath = activePlanFilePath.replace(".md", "-other.md");

        assertTrue(restrictions.denialReason(context,
                toolCall(ToolNames.FILESYSTEM, Map.of(
                        "operation", "write_file",
                        "path", otherPlanFilePath,
                        "content", "# Other Plan")))
                .isPresent());
    }

    @Test
    void shouldDenyFilesystemMutationsOutsideLocalPlanMarkdown() {
        planService.activatePlanMode(SESSION, "chat-1", null);

        assertTrue(restrictions.denialReason(context,
                toolCall(ToolNames.FILESYSTEM, Map.of(
                        "operation", "write_file",
                        "path", "src/Main.java",
                        "content", "class Main {}")))
                .isPresent());
        assertTrue(restrictions.denialReason(context,
                toolCall(ToolNames.FILESYSTEM, Map.of("operation", "create_directory", "path", "tmp")))
                .isPresent());
        assertTrue(restrictions.denialReason(context,
                toolCall(ToolNames.FILESYSTEM, Map.of("operation", "delete", "path", "README.md")))
                .isPresent());
        assertTrue(restrictions.denialReason(context,
                toolCall(ToolNames.FILESYSTEM, Map.of("operation", "send_file", "path", "README.md")))
                .isPresent());
    }

    @Test
    void shouldRejectLocalPlanMarkdownPathTraversalAndNestedFiles() {
        planService.activatePlanMode(SESSION, "chat-1", null);

        assertTrue(restrictions.denialReason(context,
                toolCall(ToolNames.FILESYSTEM, Map.of(
                        "operation", "write_file",
                        "path", "../.golemcore/plans/feature.md",
                        "content", "# Plan")))
                .isPresent());
        assertTrue(restrictions.denialReason(context,
                toolCall(ToolNames.FILESYSTEM, Map.of(
                        "operation", "write_file",
                        "path", ".golemcore/plans/nested/feature.md",
                        "content", "# Plan")))
                .isPresent());
    }

    private Message.ToolCall toolCall(String name, Map<String, Object> arguments) {
        return Message.ToolCall.builder()
                .id("tc-1")
                .name(name)
                .arguments(arguments)
                .build();
    }
}
