package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicTierSystemTest {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";
    private static final String TIER_DEFAULT = "default";
    private static final String TIER_CODING = "coding";
    private static final String TOOL_CALL_ID = "tc1";
    private static final String TOOL_SHELL = "shell";
    private static final String TOOL_FILESYSTEM = "filesystem";
    private static final String CONTENT_HELLO = "Hello";
    private static final String ARG_COMMAND = "command";

    private BotProperties properties;
    private UserPreferencesService preferencesService;
    private DynamicTierSystem system;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        properties.getRouter().setDynamicTierEnabled(true);
        preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getPreferences()).thenReturn(UserPreferences.builder().build());
        system = new DynamicTierSystem(properties, preferencesService);
    }

    @Test
    void nameAndOrder() {
        assertEquals("DynamicTierSystem", system.getName());
        assertEquals(25, system.getOrder());
    }

    @Test
    void isEnabledReflectsConfig() {
        assertTrue(system.isEnabled());
        properties.getRouter().setDynamicTierEnabled(false);
        assertFalse(system.isEnabled());
    }

    @Test
    void shouldNotProcessWhenTierForceEnabled() {
        when(preferencesService.getPreferences()).thenReturn(
                UserPreferences.builder().modelTier("smart").tierForce(true).build());
        AgentContext context = buildContext(1, "smart", List.of());
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessIteration0() {
        AgentContext context = buildContext(0, TIER_DEFAULT, List.of());
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenAlreadyCoding() {
        AgentContext context = buildContext(1, TIER_CODING, List.of());
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenAlreadyDeep() {
        AgentContext context = buildContext(1, "deep", List.of());
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldProcessIteration1WithDefaultTier() {
        AgentContext context = buildContext(1, TIER_DEFAULT, List.of());
        assertTrue(system.shouldProcess(context));
    }

    @Test
    void noCodingSignalsAfterUserMessage_tierUnchanged() {
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("What's the weather?").build(),
                Message.builder().role(ROLE_ASSISTANT).content("It's sunny.").build());

        AgentContext context = buildContext(1, TIER_DEFAULT, messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_DEFAULT, result.getModelTier());
    }

    // --- Coding signal detection (after user message) ---

    @Test
    void fileSystemWritePyUpgradesToCoding() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of("operation", "write_file", "path", "script.py"))
                .build();

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("Write a python script").build(),
                Message.builder().role(ROLE_ASSISTANT).toolCalls(List.of(toolCall)).build());

        AgentContext context = buildContext(1, TIER_DEFAULT, messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_CODING, result.getModelTier());
    }

    @Test
    void fileSystemReadJavaUpgradesToCoding() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name("file_system")
                .arguments(Map.of("operation", "read_file", "path", "/project/Main.java"))
                .build();

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("Read the java file").build(),
                Message.builder().role(ROLE_ASSISTANT).toolCalls(List.of(toolCall)).build());

        AgentContext context = buildContext(1, TIER_DEFAULT, messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_CODING, result.getModelTier());
    }

    @Test
    void shellPythonCommandUpgradesToCoding() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "python script.py"))
                .build();

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("Run the script").build(),
                Message.builder().role(ROLE_ASSISTANT).toolCalls(List.of(toolCall)).build());

        AgentContext context = buildContext(1, TIER_DEFAULT, messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_CODING, result.getModelTier());
    }

    @Test
    void shellNpmCommandUpgradesToCoding() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "npm install express"))
                .build();

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("Install express").build(),
                Message.builder().role(ROLE_ASSISTANT).toolCalls(List.of(toolCall)).build());

        AgentContext context = buildContext(1, "balanced", messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_CODING, result.getModelTier());
    }

    @Test
    void toolResultWithTracebackUpgradesToCoding() {
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("Run the script").build(),
                Message.builder()
                        .role(ROLE_TOOL)
                        .toolName(TOOL_SHELL)
                        .content(
                                "Traceback (most recent call last):\n  File \"main.py\", line 1\n    print(x\n        ^\nSyntaxError: unexpected EOF")
                        .build());

        AgentContext context = buildContext(1, TIER_DEFAULT, messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_CODING, result.getModelTier());
    }

    @Test
    void toolResultWithJavaStackTraceUpgradesToCoding() {
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("Build the project").build(),
                Message.builder()
                        .role(ROLE_TOOL)
                        .toolName(TOOL_SHELL)
                        .content(
                                "Exception in thread \"main\" java.lang.NullPointerException\n    at com.example.Main.run(Main.java:42)")
                        .build());

        AgentContext context = buildContext(1, "smart", messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_CODING, result.getModelTier());
    }

    @Test
    void nonCodeFileDoesNotTrigger() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_FILESYSTEM)
                .arguments(Map.of("operation", "write_file", "path", "notes.txt"))
                .build();

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("Write notes").build(),
                Message.builder().role(ROLE_ASSISTANT).toolCalls(List.of(toolCall)).build());

        AgentContext context = buildContext(1, TIER_DEFAULT, messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_DEFAULT, result.getModelTier());
    }

    @Test
    void nonCodeShellCommandDoesNotTrigger() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "ls -la"))
                .build();

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("List files").build(),
                Message.builder().role(ROLE_ASSISTANT).toolCalls(List.of(toolCall)).build());

        AgentContext context = buildContext(1, TIER_DEFAULT, messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_DEFAULT, result.getModelTier());
    }

    // --- Only scans current run (after last user message) ---

    @Test
    void codingSignalsInOldHistoryIgnored() {
        // Old history has coding activity, but it's before the current user message
        Message.ToolCall oldCodingCall = Message.ToolCall.builder()
                .id("tc0")
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "python train.py"))
                .build();

        List<Message> messages = new ArrayList<>();
        // Old conversation turn with coding
        messages.add(Message.builder().role(ROLE_USER).content("Train the model").build());
        messages.add(Message.builder().role(ROLE_ASSISTANT).toolCalls(List.of(oldCodingCall)).build());
        messages.add(Message.builder().role(ROLE_TOOL).toolName(TOOL_SHELL).content("Training complete").build());
        messages.add(Message.builder().role(ROLE_ASSISTANT).content("Training finished!").build());
        // Current turn — no coding
        messages.add(Message.builder().role(ROLE_USER).content("What time is it?").build());
        messages.add(Message.builder().role(ROLE_ASSISTANT).content("Let me check...").build());

        AgentContext context = buildContext(1, TIER_DEFAULT, messages);
        AgentContext result = system.process(context);

        // Should NOT upgrade — coding was in old history, not current run
        assertEquals(TIER_DEFAULT, result.getModelTier());
    }

    @Test
    void codingSignalsInCurrentRunDetected() {
        List<Message> messages = new ArrayList<>();
        // Old non-coding turn
        messages.add(Message.builder().role(ROLE_USER).content(CONTENT_HELLO).build());
        messages.add(Message.builder().role(ROLE_ASSISTANT).content("Hi!").build());
        // Current turn — has coding
        messages.add(Message.builder().role(ROLE_USER).content("Write a script").build());

        Message.ToolCall codingCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "cargo build"))
                .build();
        messages.add(Message.builder().role(ROLE_ASSISTANT).toolCalls(List.of(codingCall)).build());

        AgentContext context = buildContext(1, TIER_DEFAULT, messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_CODING, result.getModelTier());
    }

    @Test
    void noUserMessageInHistory_noUpgrade() {
        // Only assistant/tool messages, no user message — nothing to scan
        List<Message> messages = List.of(
                Message.builder().role(ROLE_ASSISTANT).content(CONTENT_HELLO).build(),
                Message.builder().role(ROLE_TOOL).toolName(TOOL_SHELL)
                        .content("Traceback...SyntaxError").build());

        AgentContext context = buildContext(1, TIER_DEFAULT, messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_DEFAULT, result.getModelTier());
    }

    @Test
    void userMessageIsLastMessage_noToolCalls_noUpgrade() {
        // User message is the very last message — nothing after it to scan
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("Write python code").build());

        AgentContext context = buildContext(1, TIER_DEFAULT, messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_DEFAULT, result.getModelTier());
    }

    // --- Edge cases ---

    @Test
    void disabledSystemSkips() {
        properties.getRouter().setDynamicTierEnabled(false);
        assertFalse(system.isEnabled());
    }

    @Test
    void emptyMessagesNoChange() {
        AgentContext context = buildContext(1, TIER_DEFAULT, List.of());
        AgentContext result = system.process(context);
        assertEquals(TIER_DEFAULT, result.getModelTier());
    }

    @Test
    void nullMessagesNoChange() {
        AgentContext context = AgentContext.builder()
                .session(buildSession())
                .messages(null)
                .currentIteration(1)
                .modelTier(TIER_DEFAULT)
                .build();

        AgentContext result = system.process(context);
        assertEquals(TIER_DEFAULT, result.getModelTier());
    }

    @Test
    void nullToolCallArguments_noException() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_FILESYSTEM)
                .arguments(null)
                .build();

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("Do something").build(),
                Message.builder().role(ROLE_ASSISTANT).toolCalls(List.of(toolCall)).build());

        AgentContext context = buildContext(1, TIER_DEFAULT, messages);
        AgentContext result = system.process(context);

        assertEquals(TIER_DEFAULT, result.getModelTier());
    }

    // --- Unit tests for helper methods ---

    @Test
    void dockerfileDetectedAsCodeFile() {
        assertTrue(system.isCodeFile("Dockerfile"));
        assertTrue(system.isCodeFile("/app/Dockerfile"));
    }

    @Test
    void makefileDetectedAsCodeFile() {
        assertTrue(system.isCodeFile("Makefile"));
        assertTrue(system.isCodeFile("/project/Makefile"));
    }

    @Test
    void isCodeCommand_exactMatch() {
        assertTrue(system.isCodeCommand("python"));
        assertTrue(system.isCodeCommand("node"));
        assertTrue(system.isCodeCommand("cargo"));
    }

    @Test
    void isCodeCommand_withArgs() {
        assertTrue(system.isCodeCommand("python -m pytest"));
        assertTrue(system.isCodeCommand("go run main.go"));
        assertTrue(system.isCodeCommand("mvn clean package"));
    }

    @Test
    void isCodeCommand_nonCodeCommand() {
        assertFalse(system.isCodeCommand("ls -la"));
        assertFalse(system.isCodeCommand("cat file.txt"));
        assertFalse(system.isCodeCommand("echo hello"));
    }

    @Test
    void hasCodePatterns_nullContent() {
        assertFalse(system.hasCodePatterns(null));
    }

    @Test
    void hasCodePatterns_rustError() {
        assertTrue(system.hasCodePatterns("error[E0308]: mismatched types"));
    }

    @Test
    void hasCodePatterns_goPanic() {
        assertTrue(system.hasCodePatterns("goroutine 1 [running]:\npanic: runtime error"));
    }

    @Test
    void getMessagesAfterLastUserMessage_basic() {
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content(CONTENT_HELLO).build(),
                Message.builder().role(ROLE_ASSISTANT).content("Hi").build(),
                Message.builder().role(ROLE_TOOL).toolName("t").content("result").build());
        List<Message> result = system.getMessagesAfterLastUserMessage(messages);
        assertEquals(2, result.size());
        assertEquals(ROLE_ASSISTANT, result.get(0).getRole());
        assertEquals(ROLE_TOOL, result.get(1).getRole());
    }

    @Test
    void getMessagesAfterLastUserMessage_multipleUserMessages() {
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("First").build(),
                Message.builder().role(ROLE_ASSISTANT).content("Reply 1").build(),
                Message.builder().role(ROLE_USER).content("Second").build(),
                Message.builder().role(ROLE_ASSISTANT).content("Reply 2").build());
        List<Message> result = system.getMessagesAfterLastUserMessage(messages);
        assertEquals(1, result.size());
        assertEquals("Reply 2", result.get(0).getContent());
    }

    @Test
    void getMessagesAfterLastUserMessage_userIsLast() {
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content(CONTENT_HELLO).build());
        List<Message> result = system.getMessagesAfterLastUserMessage(messages);
        assertTrue(result.isEmpty());
    }

    @Test
    void getMessagesAfterLastUserMessage_noUserMessage() {
        List<Message> messages = List.of(
                Message.builder().role(ROLE_ASSISTANT).content("Hi").build());
        List<Message> result = system.getMessagesAfterLastUserMessage(messages);
        assertTrue(result.isEmpty());
    }

    private AgentContext buildContext(int iteration, String modelTier, List<Message> messages) {
        return AgentContext.builder()
                .session(buildSession())
                .messages(new ArrayList<>(messages))
                .currentIteration(iteration)
                .modelTier(modelTier)
                .build();
    }

    private AgentSession buildSession() {
        return AgentSession.builder()
                .id("test-session")
                .channelType("telegram")
                .chatId("123")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
