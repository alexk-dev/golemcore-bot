package me.golemcore.bot.domain.tracing;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolExecutionTrace;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionTraceExtractorTest {

    private final ToolExecutionTraceExtractor extractor = new ToolExecutionTraceExtractor();

    @Test
    void shouldCaptureThreeGenericArgumentsWithoutLeakingMore() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("alpha", "one");
        arguments.put("beta", "two");
        arguments.put("gamma", "three");
        arguments.put("delta", "four");

        ToolExecutionTrace trace = extractor.extract(toolCall("custom_tool", arguments),
                success("ok", Map.of()), 42);

        assertEquals("tool", trace.family());
        assertTrue(trace.success());
        assertEquals(42, trace.durationMs());
        assertEquals("used custom_tool (alpha=\"one\", beta=\"two\", gamma=\"three\")", trace.action());
        assertEquals("one", trace.details().get("alpha"));
        assertEquals("two", trace.details().get("beta"));
        assertEquals("three", trace.details().get("gamma"));
        assertFalse(trace.details().containsKey("delta"));
        assertEquals("custom_tool", trace.details().get("tool"));
    }

    @Test
    void shouldPreferResultWorkdirAndExitCodeForShellTraces() {
        ToolExecutionTrace trace = extractor.extract(toolCall("shell", Map.of("command", "  ./mvnw   test  ")),
                success("failed", Map.of("workdir", "/workspace/project", "exitCode", 1)), 350);

        assertEquals("shell", trace.family());
        assertTrue(trace.success());
        assertEquals("ran `./mvnw test` in /workspace/project", trace.action());
        assertEquals("./mvnw test", trace.details().get("command"));
        assertEquals("/workspace/project", trace.details().get("workdir"));
        assertEquals(1, trace.details().get("exitCode"));
    }

    @Test
    void shouldNormalizeSearchAndBrowseFamilies() {
        ToolExecutionTrace search = extractor.extract(
                toolCall("perplexity_search", Map.of("question", "release notes")),
                success("ok", Map.of()), 10);
        ToolExecutionTrace browse = extractor.extract(
                toolCall("firecrawl_fetch", Map.of("url", "https://example.test")),
                failure(), 12);

        assertEquals("search", search.family());
        assertEquals("searched for \"release notes\"", search.action());
        assertTrue(search.success());
        assertEquals("browse", browse.family());
        assertEquals("checked https://example.test", browse.action());
        assertFalse(browse.success());
    }

    @Test
    void shouldIgnoreNonMapResultDataAndUseSafeDefaults() {
        ToolExecutionTrace trace = extractor.extract(null,
                new ToolExecutionOutcome("call-1", "ignored", ToolResult.success("ok", "not-a-map"), "ok", false,
                        null),
                0);

        assertEquals("tool", trace.toolName());
        assertEquals("tool", trace.family());
        assertEquals("used tool", trace.action());
        assertTrue(trace.success());
        assertEquals("tool", trace.details().get("tool"));
    }

    private Message.ToolCall toolCall(String name, Map<String, Object> arguments) {
        return Message.ToolCall.builder()
                .id("call-1")
                .name(name)
                .arguments(arguments)
                .build();
    }

    private ToolExecutionOutcome success(String output, Object data) {
        return new ToolExecutionOutcome("call-1", "tool", ToolResult.success(output, data), output, false, null);
    }

    private ToolExecutionOutcome failure() {
        return new ToolExecutionOutcome("call-1", "tool", ToolResult.failure("boom"), "boom", false, null);
    }
}
