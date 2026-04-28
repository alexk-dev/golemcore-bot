package me.golemcore.bot.domain.system.toolloop.repeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolNames;
import org.junit.jupiter.api.Test;

class ToolUseFingerprintServiceTest {

    private final ToolUseFingerprintService service = new ToolUseFingerprintService();

    @Test
    void canonicalizesJsonArgumentsByKeyOrder() {
        Message.ToolCall first = toolCall("filesystem", Map.of("operation", "read_file", "path", "README.md"));
        Message.ToolCall second = toolCall("filesystem", Map.of("path", "README.md", "operation", "read_file"));

        assertEquals(service.fingerprint(first).stableKey(), service.fingerprint(second).stableKey());
    }

    @Test
    void normalizesFilesystemPaths() {
        Message.ToolCall first = toolCall("filesystem",
                Map.of("operation", "read_file", "path", "./docs/../README.md"));
        Message.ToolCall second = toolCall("filesystem", Map.of("operation", "read_file", "path", "README.md"));

        assertEquals(service.fingerprint(first).stableKey(), service.fingerprint(second).stableKey());
    }

    @Test
    void includesShellWorkingDirectoryInFingerprint() {
        Message.ToolCall first = toolCall(ToolNames.SHELL, Map.of("command", "ls -la", "cwd", "/workspace/a"));
        Message.ToolCall second = toolCall(ToolNames.SHELL, Map.of("command", "ls -la", "cwd", "/workspace/b"));

        assertNotEquals(service.fingerprint(first).stableKey(), service.fingerprint(second).stableKey());
    }

    @Test
    void normalizesShellWorkingDirectoryForEquivalentPaths() {
        Message.ToolCall first = toolCall(ToolNames.SHELL, Map.of("command", "ls -la", "cwd", "."));
        Message.ToolCall second = toolCall(ToolNames.SHELL, Map.of("command", "ls -la", "cwd", "./"));
        Message.ToolCall third = toolCall(ToolNames.SHELL, Map.of("command", "ls -la", "cwd", "docs/.."));

        assertEquals(service.fingerprint(first).stableKey(), service.fingerprint(second).stableKey());
        assertEquals(service.fingerprint(first).stableKey(), service.fingerprint(third).stableKey());
    }

    @Test
    void normalizesShellWorkdirAliasesForEquivalentPaths() {
        Message.ToolCall first = toolCall(ToolNames.SHELL, Map.of("command", "pwd", "workdir", "src/../src"));
        Message.ToolCall second = toolCall(ToolNames.SHELL, Map.of("command", "pwd", "workdir", "src"));
        Message.ToolCall third = toolCall(ToolNames.SHELL,
                Map.of("command", "pwd", "workingDirectory", "src/."));

        assertEquals(service.fingerprint(first).stableKey(), service.fingerprint(second).stableKey());
        assertEquals(service.fingerprint(first).stableKey(), service.fingerprint(third).stableKey());
    }

    @Test
    void redactsSecretLikeArgumentsBeforeHashingAndDebugSnapshot() {
        Message.ToolCall first = toolCall("http.get",
                Map.of("url", "https://example.test", "apiKey", "secret-one", "password", "secret-two"));
        Message.ToolCall second = toolCall("http.get",
                Map.of("url", "https://example.test", "apiKey", "secret-three", "password", "secret-four"));

        ToolUseFingerprint fingerprint = service.fingerprint(first);

        assertEquals(fingerprint.stableKey(), service.fingerprint(second).stableKey());
        assertFalse(fingerprint.debugArguments().contains("secret-one"));
        assertFalse(fingerprint.debugArguments().contains("secret-two"));
        assertTrue(fingerprint.debugArguments().contains("<redacted>"));
    }

    @Test
    void excludesVolatileFields() {
        Message.ToolCall first = toolCall("filesystem",
                Map.of("operation", "read_file", "path", "README.md", "requestId", "req-1", "timestamp", "t1"));
        Message.ToolCall second = toolCall("filesystem",
                Map.of("operation", "read_file", "path", "README.md", "requestId", "req-2", "timestamp", "t2"));

        assertEquals(service.fingerprint(first).stableKey(), service.fingerprint(second).stableKey());
    }

    @Test
    void classifiesReadLikeToolsAsObserveAndUnknownToolsAsExecuteUnknown() {
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall("filesystem", Map.of("operation", "read_file", "path", "README.md")))
                        .category());
        assertEquals(ToolUseCategory.POLL,
                service.fingerprint(toolCall("job.status", Map.of("jobId", "42"))).category());
        assertEquals(ToolUseCategory.EXECUTE_UNKNOWN,
                service.fingerprint(toolCall("external.sidecar", Map.of("input", "value"))).category());
    }

    @Test
    void classifiesControlShellMutatingFilesystemAndGoalLikeTools() {
        assertEquals(ToolUseCategory.CONTROL, service.fingerprint(toolCall(ToolNames.PLAN_EXIT, Map.of())).category());
        assertEquals(ToolUseCategory.EXECUTE_UNKNOWN,
                service.fingerprint(toolCall(ToolNames.SHELL, Map.of("command", "ls"))).category());
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT,
                service.fingerprint(toolCall("filesystem",
                        Map.of("operation", "write_file", "path", "README.md", "content", "updated"))).category());
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT,
                service.fingerprint(toolCall("goal.diary", Map.of("text", "checkpoint"))).category());
    }

    @Test
    void classifiesAppendAsNonIdempotentMutation() {
        assertEquals(ToolUseCategory.MUTATE_NON_IDEMPOTENT,
                service.fingerprint(toolCall("filesystem",
                        Map.of("operation", "append", "path", "README.md", "content", "x"))).category());
    }

    @Test
    void classifiesUnknownFilesystemOperationAsExecuteUnknown() {
        assertEquals(ToolUseCategory.EXECUTE_UNKNOWN,
                service.fingerprint(toolCall("filesystem",
                        Map.of("operation", "rename", "from", "a", "to", "b"))).category());
    }

    @Test
    void canonicalizesNestedCollectionsArraysPrimitivesAndUnknownObjects() {
        Message.ToolCall first = toolCall("filesystem",
                Map.of(
                        "operation", "read_file",
                        "path", "README.md",
                        "tags", java.util.List.of("a", "b"),
                        "ids", new String[] { "1", "2" },
                        "enabled", true,
                        "count", 2,
                        "marker", new Marker("x")));
        Message.ToolCall second = toolCall("filesystem",
                Map.of(
                        "marker", new Marker("x"),
                        "count", 2,
                        "enabled", true,
                        "ids", new String[] { "1", "2" },
                        "tags", java.util.List.of("a", "b"),
                        "path", "./README.md",
                        "operation", "read_file"));

        ToolUseFingerprint fingerprint = service.fingerprint(first);

        assertEquals(fingerprint.stableKey(), service.fingerprint(second).stableKey());
        assertTrue(fingerprint.debugArguments().contains("\"ids\":[\"1\",\"2\"]"));
        assertTrue(fingerprint.debugArguments().contains("\"marker\":\"marker:x\""));
    }

    @Test
    void escapesJsonSpecialCharactersInDebugArguments() {
        ToolUseFingerprint fingerprint = service.fingerprint(toolCall("note.read",
                Map.of("text", "\"quoted\"\\path\nnext\tcolumn", "operation", "read")));

        assertTrue(fingerprint.debugArguments().contains("\\\"quoted\\\""));
        assertTrue(fingerprint.debugArguments().contains("\\\\path"));
        assertTrue(fingerprint.debugArguments().contains("\\nnext\\tcolumn"));
    }

    @Test
    void handlesNullToolCallAndNullArguments() {
        ToolUseFingerprint nullCallFingerprint = service.fingerprint(null);
        ToolUseFingerprint nullArgumentsFingerprint = service.fingerprint(toolCall("external.sidecar", null));

        assertEquals(ToolUseCategory.EXECUTE_UNKNOWN, nullCallFingerprint.category());
        assertEquals("{}", nullArgumentsFingerprint.debugArguments());
    }

    private Message.ToolCall toolCall(String name, Map<String, Object> arguments) {
        return Message.ToolCall.builder()
                .id("call-" + name)
                .name(name)
                .arguments(arguments)
                .build();
    }

    private record Marker(String value) {

        @Override
        public String toString() {
            return "marker:" + value;
        }
    }
}
