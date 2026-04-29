package me.golemcore.bot.domain.system.toolloop.repeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void invalidFilesystemPathDoesNotThrowDuringFingerprinting() {
        Message.ToolCall call = toolCall(ToolNames.FILESYSTEM,
                Map.of("operation", "read_file", "path", "bad\u0000path"));

        ToolUseFingerprint fingerprint = assertDoesNotThrow(() -> service.fingerprint(call));

        assertEquals(ToolUseCategory.OBSERVE, fingerprint.category());
        assertTrue(fingerprint.stableKey().startsWith("filesystem:OBSERVE:"));
        assertFalse(fingerprint.debugArguments().contains("bad\u0000path"));
    }

    @Test
    void invalidShellWorkdirDoesNotThrowDuringFingerprinting() {
        Message.ToolCall call = toolCall(ToolNames.SHELL,
                Map.of("command", "pwd", "workdir", "bad\u0000dir"));

        ToolUseFingerprint fingerprint = assertDoesNotThrow(() -> service.fingerprint(call));

        assertEquals(ToolUseCategory.EXECUTE_UNKNOWN, fingerprint.category());
        assertTrue(fingerprint.stableKey().startsWith("shell:EXECUTE_UNKNOWN:"));
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
    void treatsMissingShellWorkdirAsWorkspaceRoot() {
        Message.ToolCall first = toolCall(ToolNames.SHELL, Map.of("command", "pwd"));
        Message.ToolCall second = toolCall(ToolNames.SHELL, Map.of("command", "pwd", "workdir", "."));
        Message.ToolCall third = toolCall(ToolNames.SHELL, Map.of("command", "pwd", "cwd", "./"));

        assertEquals(service.fingerprint(first).stableKey(), service.fingerprint(second).stableKey());
        assertEquals(service.fingerprint(first).stableKey(), service.fingerprint(third).stableKey());
    }

    @Test
    void conflictingShellWorkdirAliasesDoNotCollapseToSingleAlias() {
        Message.ToolCall first = toolCall(ToolNames.SHELL,
                Map.of("command", "pwd", "workdir", "src", "cwd", "."));
        Message.ToolCall second = toolCall(ToolNames.SHELL,
                Map.of("command", "pwd", "workdir", "src"));
        Message.ToolCall third = toolCall(ToolNames.SHELL,
                Map.of("command", "pwd", "cwd", "."));

        assertNotEquals(service.fingerprint(first).stableKey(), service.fingerprint(second).stableKey());
        assertNotEquals(service.fingerprint(first).stableKey(), service.fingerprint(third).stableKey());
        assertTrue(service.fingerprint(first).debugArguments().contains("cwd_aliases_conflict"));
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
        assertEquals(ToolUseCategory.EXECUTE_UNKNOWN,
                service.fingerprint(toolCall("goal.diary", Map.of("text", "checkpoint"))).category());
    }

    @Test
    void classifiesAppendAsNonIdempotentMutation() {
        assertEquals(ToolUseCategory.MUTATE_NON_IDEMPOTENT,
                service.fingerprint(toolCall("filesystem",
                        Map.of("operation", "append", "path", "README.md", "content", "x"))).category());
    }

    @Test
    void classifiesMemorySearchReadAndExpandAsObserve() {
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall(ToolNames.MEMORY, Map.of("operation", "memory_search", "query",
                        "repeat"))).category());
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall(ToolNames.MEMORY, Map.of("operation", "memory_read", "id", "m1")))
                        .category());
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall(ToolNames.MEMORY,
                        Map.of("operation", "memory_expand_section", "section", "semantic"))).category());
    }

    @Test
    void classifiesMemoryAddUpdatePromoteForgetAsMutation() {
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT,
                service.fingerprint(toolCall(ToolNames.MEMORY, Map.of("operation", "memory_add", "text", "note")))
                        .category());
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT,
                service.fingerprint(toolCall(ToolNames.MEMORY, Map.of("operation", "memory_update", "id", "m1")))
                        .category());
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT,
                service.fingerprint(toolCall(ToolNames.MEMORY, Map.of("operation", "memory_promote", "id", "m1")))
                        .category());
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT,
                service.fingerprint(toolCall(ToolNames.MEMORY, Map.of("operation", "memory_forget", "id", "m1")))
                        .category());
    }

    @Test
    void classifiesGoalManagementReadAndMutationOperationsExplicitly() {
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall(ToolNames.GOAL_MANAGEMENT, Map.of("operation", "list_goals")))
                        .category());
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT,
                service.fingerprint(toolCall(ToolNames.GOAL_MANAGEMENT,
                        Map.of("operation", "write_diary", "content", "checkpoint"))).category());
    }

    @Test
    void classifiesHiveToolsExplicitly() {
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall(ToolNames.HIVE_GET_CURRENT_CONTEXT, Map.of())).category());
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall(ToolNames.HIVE_GET_CARD, Map.of("cardId", "card-1"))).category());
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall(ToolNames.HIVE_SEARCH_CARDS, Map.of("query", "bug"))).category());
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT,
                service.fingerprint(toolCall(ToolNames.HIVE_POST_THREAD_MESSAGE,
                        Map.of("threadId", "thread-1", "message", "updated"))).category());
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT,
                service.fingerprint(toolCall(ToolNames.HIVE_REQUEST_REVIEW, Map.of("cardId", "card-1"))).category());
    }

    @Test
    void classifiesScheduleSessionActionByOperation() {
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall(ToolNames.SCHEDULE_SESSION_ACTION, Map.of("operation", "list")))
                        .category());
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT,
                service.fingerprint(toolCall(ToolNames.SCHEDULE_SESSION_ACTION,
                        Map.of("operation", "create", "message", "check later"))).category());
    }

    @Test
    void classifiesFilesystemListDirectoryAndFileInfoAsObserve() {
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall(ToolNames.FILESYSTEM,
                        Map.of("operation", "list_directory", "path", "src"))).category());
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall(ToolNames.FILESYSTEM,
                        Map.of("operation", "file_info", "path", "pom.xml"))).category());
    }

    @Test
    void classifiesFirstPartyPluginObservationTools() {
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall("browse", Map.of("url", "https://example.test"))).category());
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall("firecrawl_scrape", Map.of("url", "https://example.test")))
                        .category());
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall("perplexity_ask", Map.of("query", "latest docs"))).category());
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall("weather", Map.of("location", "San Francisco"))).category());
    }

    @Test
    void classifiesMailPluginToolsConservatively() {
        assertEquals(ToolUseCategory.OBSERVE,
                service.fingerprint(toolCall("imap", Map.of("operation", "search", "query", "from:ops")))
                        .category());
        assertEquals(ToolUseCategory.MUTATE_NON_IDEMPOTENT,
                service.fingerprint(toolCall("smtp", Map.of("to", "ops@example.test", "body", "hello")))
                        .category());
    }

    @Test
    void classifiesDynamicObservationToolsWithoutDigestProgress() {
        ToolUseFingerprint datetime = service.fingerprint(toolCall("datetime", Map.of()));
        ToolUseFingerprint weather = service.fingerprint(toolCall("weather", Map.of("location", "Santo Domingo")));
        ToolUseFingerprint browse = service.fingerprint(toolCall("browse", Map.of("url", "https://example.test")));
        ToolUseFingerprint braveSearch = service.fingerprint(toolCall("brave_search", Map.of("query", "updates")));
        ToolUseFingerprint tavilySearch = service.fingerprint(toolCall("tavily_search", Map.of("query", "updates")));
        ToolUseFingerprint firecrawl = service.fingerprint(toolCall("firecrawl_scrape",
                Map.of("url", "https://example.test")));
        ToolUseFingerprint perplexity = service.fingerprint(toolCall("perplexity_ask",
                Map.of("question", "latest status")));
        ToolUseFingerprint imap = service.fingerprint(toolCall("imap", Map.of("operation", "search")));

        assertFalse(datetime.outputDigestChangeResetsRepeatCount());
        assertTrue(datetime.observedDomains().contains(ToolStateDomain.TIME));
        assertFalse(weather.outputDigestChangeResetsRepeatCount());
        assertTrue(weather.observedDomains().contains(ToolStateDomain.WEATHER));
        assertFalse(browse.outputDigestChangeResetsRepeatCount());
        assertTrue(browse.observedDomains().contains(ToolStateDomain.WEB_REMOTE));
        assertFalse(braveSearch.outputDigestChangeResetsRepeatCount());
        assertTrue(braveSearch.observedDomains().contains(ToolStateDomain.WEB_REMOTE));
        assertFalse(tavilySearch.outputDigestChangeResetsRepeatCount());
        assertTrue(tavilySearch.observedDomains().contains(ToolStateDomain.WEB_REMOTE));
        assertFalse(firecrawl.outputDigestChangeResetsRepeatCount());
        assertTrue(firecrawl.observedDomains().contains(ToolStateDomain.WEB_REMOTE));
        assertFalse(perplexity.outputDigestChangeResetsRepeatCount());
        assertTrue(perplexity.observedDomains().contains(ToolStateDomain.WEB_REMOTE));
        assertFalse(imap.outputDigestChangeResetsRepeatCount());
        assertTrue(imap.observedDomains().contains(ToolStateDomain.MAIL));
    }

    @Test
    void classifiesMailSendAsNonIdempotentMutation() {
        ToolUseFingerprint smtp = service.fingerprint(toolCall("smtp", Map.of("to", "a@example.test")));

        assertEquals(ToolUseCategory.MUTATE_NON_IDEMPOTENT, smtp.category());
        assertTrue(smtp.invalidatedDomains().contains(ToolStateDomain.MAIL));
    }

    @Test
    void classifiesPlanToolsWithPlanDomain() {
        ToolUseFingerprint planGet = service.fingerprint(toolCall("plan_get", Map.of()));
        ToolUseFingerprint planSet = service.fingerprint(toolCall("plan_set_content",
                Map.of("plan_markdown", "- [ ] Test")));

        assertEquals(ToolUseCategory.OBSERVE, planGet.category());
        assertTrue(planGet.observedDomains().contains(ToolStateDomain.PLAN));
        assertTrue(planGet.outputDigestChangeResetsRepeatCount());
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT, planSet.category());
        assertTrue(planSet.invalidatedDomains().contains(ToolStateDomain.PLAN));
    }

    @Test
    void classifiesSkillManagementToolsWithSkillsDomain() {
        ToolUseFingerprint list = service.fingerprint(toolCall("skill_management",
                Map.of("operation", "list_skills")));
        ToolUseFingerprint create = service.fingerprint(toolCall("skill_management",
                Map.of("operation", "create_skill", "name", "reviewer")));
        ToolUseFingerprint delete = service.fingerprint(toolCall("skill_management",
                Map.of("operation", "delete_skill", "name", "reviewer")));

        assertEquals(ToolUseCategory.OBSERVE, list.category());
        assertTrue(list.observedDomains().contains(ToolStateDomain.SKILLS));
        assertTrue(list.outputDigestChangeResetsRepeatCount());
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT, create.category());
        assertTrue(create.invalidatedDomains().contains(ToolStateDomain.SKILLS));
        assertEquals(ToolUseCategory.MUTATE_NON_IDEMPOTENT, delete.category());
        assertTrue(delete.invalidatedDomains().contains(ToolStateDomain.SKILLS));
    }

    @Test
    void classifiesSessionControlToolsExplicitly() {
        assertEquals(ToolUseCategory.CONTROL, service.fingerprint(toolCall(ToolNames.PLAN_EXIT, Map.of()))
                .category());
        ToolUseFingerprint skillTransition = service.fingerprint(toolCall("skill_transition",
                Map.of("target_skill", "reviewer")));
        ToolUseFingerprint setTier = service.fingerprint(toolCall("set_tier", Map.of("tier", "coding")));
        ToolUseFingerprint sendVoice = service.fingerprint(toolCall("send_voice", Map.of("text", "hello")));

        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT, skillTransition.category());
        assertTrue(skillTransition.invalidatedDomains().contains(ToolStateDomain.SESSION_CONTROL));
        assertEquals(ToolUseCategory.MUTATE_IDEMPOTENT, setTier.category());
        assertTrue(setTier.invalidatedDomains().contains(ToolStateDomain.SESSION_CONTROL));
        assertEquals(ToolUseCategory.MUTATE_NON_IDEMPOTENT, sendVoice.category());
        assertTrue(sendVoice.invalidatedDomains().contains(ToolStateDomain.SESSION_CONTROL));
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
