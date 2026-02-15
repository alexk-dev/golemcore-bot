package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolConfirmationPolicyTest {

    private static final String TOOL_CALL_ID = "1";
    private static final String TOOL_FILESYSTEM = "filesystem";
    private static final String TOOL_SHELL = "shell";
    private static final String TOOL_SKILL_MGMT = "skill_management";
    private static final String ARG_OPERATION = "operation";
    private static final String ARG_COMMAND = "command";
    private static final String ARG_PATH = "path";
    private static final String TEST_FILE = "test.txt";

    private ToolConfirmationPolicy policy;

    @BeforeEach
    void setUp() {
        BotProperties properties = new BotProperties();
        properties.getSecurity().getToolConfirmation().setEnabled(true);
        policy = new ToolConfirmationPolicy(properties);
    }

    @Test
    void filesystemDeleteRequiresConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of(ARG_OPERATION, "delete", ARG_PATH, TEST_FILE))
                .build();
        assertTrue(policy.requiresConfirmation(toolCall));
    }

    @Test
    void filesystemReadDoesNotRequireConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of(ARG_OPERATION, "read_file", ARG_PATH, TEST_FILE))
                .build();
        assertFalse(policy.requiresConfirmation(toolCall));
    }

    @Test
    void filesystemWriteDoesNotRequireConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of(ARG_OPERATION, "write_file", ARG_PATH, TEST_FILE, "content", "hello"))
                .build();
        assertFalse(policy.requiresConfirmation(toolCall));
    }

    @Test
    void shellAlwaysRequiresConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "ls -la"))
                .build();
        assertTrue(policy.requiresConfirmation(toolCall));
    }

    @Test
    void skillManagementDeleteRequiresConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SKILL_MGMT)
                .arguments(Map.of(ARG_OPERATION, "delete_skill", "name", "test"))
                .build();
        assertTrue(policy.requiresConfirmation(toolCall));
    }

    @Test
    void skillManagementListDoesNotRequireConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SKILL_MGMT)
                .arguments(Map.of(ARG_OPERATION, "list_skills"))
                .build();
        assertFalse(policy.requiresConfirmation(toolCall));
    }

    @Test
    void unknownToolDoesNotRequireConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name("datetime")
                .arguments(Map.of(ARG_OPERATION, "now"))
                .build();
        assertFalse(policy.requiresConfirmation(toolCall));
    }

    @Test
    void disabledPolicyNeverRequiresConfirmation() {
        BotProperties props = new BotProperties();
        props.getSecurity().getToolConfirmation().setEnabled(false);
        ToolConfirmationPolicy disabledPolicy = new ToolConfirmationPolicy(props);

        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "rm -rf test"))
                .build();
        assertFalse(disabledPolicy.requiresConfirmation(toolCall));
    }

    @Test
    void describeActionForFilesystemDelete() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of(ARG_OPERATION, "delete", ARG_PATH, TEST_FILE))
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("Delete file"));
        assertTrue(description.contains(TEST_FILE));
    }

    @Test
    void describeActionForShellCommand() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "echo hello"))
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("Run command"));
        assertTrue(description.contains("echo hello"));
    }

    @Test
    void describeActionForShellTruncatesLongCommand() {
        String longCommand = "a".repeat(100);
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, longCommand))
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("..."));
        assertTrue(description.length() < longCommand.length() + 20);
    }

    @Test
    void describeActionForSkillDelete() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SKILL_MGMT)
                .arguments(Map.of(ARG_OPERATION, "delete_skill", "name", "my-skill"))
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("Delete skill"));
        assertTrue(description.contains("my-skill"));
    }

    @Test
    void isNotableActionReturnsTrueForShellEvenWhenDisabled() {
        BotProperties props = new BotProperties();
        props.getSecurity().getToolConfirmation().setEnabled(false);
        ToolConfirmationPolicy disabledPolicy = new ToolConfirmationPolicy(props);

        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "echo hello"))
                .build();

        assertFalse(disabledPolicy.requiresConfirmation(toolCall));
        assertTrue(disabledPolicy.isNotableAction(toolCall));
    }

    @Test
    void isNotableActionReturnsFalseForDatetime() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name("datetime")
                .arguments(Map.of(ARG_OPERATION, "now"))
                .build();
        assertFalse(policy.isNotableAction(toolCall));
    }

    @Test
    void isEnabledReflectsConfig() {
        assertTrue(policy.isEnabled());

        BotProperties props = new BotProperties();
        props.getSecurity().getToolConfirmation().setEnabled(false);
        ToolConfirmationPolicy disabledPolicy = new ToolConfirmationPolicy(props);
        assertFalse(disabledPolicy.isEnabled());
    }

    // ==================== Null args edge cases ====================

    @Test
    void shouldNotRequireConfirmationWhenFilesystemArgsNull() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_FILESYSTEM)
                .arguments(null)
                .build();
        assertFalse(policy.requiresConfirmation(toolCall));
    }

    @Test
    void shouldNotRequireConfirmationWhenSkillManagementArgsNull() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SKILL_MGMT)
                .arguments(null)
                .build();
        assertFalse(policy.requiresConfirmation(toolCall));
    }

    // ==================== describeAction edge cases ====================

    @Test
    void shouldDescribeFilesystemNonDeleteAction() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of(ARG_OPERATION, "read_file", ARG_PATH, TEST_FILE))
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("File operation"));
        assertTrue(description.contains("read_file"));
    }

    @Test
    void shouldDescribeFilesystemWithNullArgs() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_FILESYSTEM)
                .arguments(null)
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("File operation"));
    }

    @Test
    void shouldDescribeFilesystemDeleteWithNullPath() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of(ARG_OPERATION, "delete"))
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("Delete file"));
        assertTrue(description.contains("unknown"));
    }

    @Test
    void shouldDescribeShellWithNullArgs() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(null)
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("Run command"));
    }

    @Test
    void shouldDescribeSkillNonDeleteAction() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SKILL_MGMT)
                .arguments(Map.of(ARG_OPERATION, "list_skills"))
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("Skill operation"));
        assertTrue(description.contains("list_skills"));
    }

    @Test
    void shouldDescribeSkillWithNullArgs() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SKILL_MGMT)
                .arguments(null)
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("Skill operation"));
    }

    @Test
    void shouldDescribeSkillDeleteWithNullName() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SKILL_MGMT)
                .arguments(Map.of(ARG_OPERATION, "delete_skill"))
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("Delete skill"));
        assertTrue(description.contains("unknown"));
    }

    @Test
    void shouldDescribeUnknownTool() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name("custom_tool")
                .arguments(Map.of("key", "value"))
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("custom_tool"));
    }

    @Test
    void shouldDescribeShellCommandExactlyAtThreshold() {
        String command = "a".repeat(80);
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, command))
                .build();
        String description = policy.describeAction(toolCall);
        assertFalse(description.contains("..."));
    }
}
