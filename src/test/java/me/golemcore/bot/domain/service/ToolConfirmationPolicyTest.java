package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolConfirmationPolicyTest {

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
                .id("1")
                .name("filesystem")
                .arguments(Map.of("operation", "delete", "path", "test.txt"))
                .build();
        assertTrue(policy.requiresConfirmation(toolCall));
    }

    @Test
    void filesystemReadDoesNotRequireConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("1")
                .name("filesystem")
                .arguments(Map.of("operation", "read_file", "path", "test.txt"))
                .build();
        assertFalse(policy.requiresConfirmation(toolCall));
    }

    @Test
    void filesystemWriteDoesNotRequireConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("1")
                .name("filesystem")
                .arguments(Map.of("operation", "write_file", "path", "test.txt", "content", "hello"))
                .build();
        assertFalse(policy.requiresConfirmation(toolCall));
    }

    @Test
    void shellAlwaysRequiresConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("1")
                .name("shell")
                .arguments(Map.of("command", "ls -la"))
                .build();
        assertTrue(policy.requiresConfirmation(toolCall));
    }

    @Test
    void skillManagementDeleteRequiresConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("1")
                .name("skill_management")
                .arguments(Map.of("operation", "delete_skill", "name", "test"))
                .build();
        assertTrue(policy.requiresConfirmation(toolCall));
    }

    @Test
    void skillManagementListDoesNotRequireConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("1")
                .name("skill_management")
                .arguments(Map.of("operation", "list_skills"))
                .build();
        assertFalse(policy.requiresConfirmation(toolCall));
    }

    @Test
    void unknownToolDoesNotRequireConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("1")
                .name("datetime")
                .arguments(Map.of("operation", "now"))
                .build();
        assertFalse(policy.requiresConfirmation(toolCall));
    }

    @Test
    void disabledPolicyNeverRequiresConfirmation() {
        BotProperties props = new BotProperties();
        props.getSecurity().getToolConfirmation().setEnabled(false);
        ToolConfirmationPolicy disabledPolicy = new ToolConfirmationPolicy(props);

        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("1")
                .name("shell")
                .arguments(Map.of("command", "rm -rf test"))
                .build();
        assertFalse(disabledPolicy.requiresConfirmation(toolCall));
    }

    @Test
    void describeActionForFilesystemDelete() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("1")
                .name("filesystem")
                .arguments(Map.of("operation", "delete", "path", "test.txt"))
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("Delete file"));
        assertTrue(description.contains("test.txt"));
    }

    @Test
    void describeActionForShellCommand() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("1")
                .name("shell")
                .arguments(Map.of("command", "echo hello"))
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("Run command"));
        assertTrue(description.contains("echo hello"));
    }

    @Test
    void describeActionForShellTruncatesLongCommand() {
        String longCommand = "a".repeat(100);
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("1")
                .name("shell")
                .arguments(Map.of("command", longCommand))
                .build();
        String description = policy.describeAction(toolCall);
        assertTrue(description.contains("..."));
        assertTrue(description.length() < longCommand.length() + 20);
    }

    @Test
    void describeActionForSkillDelete() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("1")
                .name("skill_management")
                .arguments(Map.of("operation", "delete_skill", "name", "my-skill"))
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
                .id("1")
                .name("shell")
                .arguments(Map.of("command", "echo hello"))
                .build();

        assertFalse(disabledPolicy.requiresConfirmation(toolCall));
        assertTrue(disabledPolicy.isNotableAction(toolCall));
    }

    @Test
    void isNotableActionReturnsFalseForDatetime() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("1")
                .name("datetime")
                .arguments(Map.of("operation", "now"))
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
}
