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

    private Message.ToolCall toolCall(String name, Map<String, Object> arguments) {
        return Message.ToolCall.builder()
                .id("call-" + name)
                .name(name)
                .arguments(arguments)
                .build();
    }
}
