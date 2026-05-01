package me.golemcore.bot.domain.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CliContractsTest {

    @Test
    void shouldExposeStableEnumValues() {
        assertEquals(0, CliExitCode.SUCCESS.processCode());
        assertEquals(1, CliExitCode.GENERAL_ERROR.processCode());
        assertEquals(2, CliExitCode.INVALID_USAGE.processCode());
        assertEquals(5, CliExitCode.PERMISSION_DENIED.processCode());
        assertEquals(8, CliExitCode.TIMEOUT.processCode());
        assertEquals(14, CliExitCode.FEATURE_UNAVAILABLE.processCode());
        assertEquals(130, CliExitCode.CANCELLED.processCode());
        assertEquals(CliExitCode.FEATURE_UNAVAILABLE, CliExitCode.fromProcessCode(14));
        assertEquals(CliExitCode.GENERAL_ERROR, CliExitCode.fromProcessCode(999));

        assertEquals("text", CliOutputFormat.TEXT.wireValue());
        assertEquals("json", CliOutputFormat.JSON.wireValue());
        assertEquals("ndjson", CliOutputFormat.NDJSON.wireValue());
        assertEquals("markdown", CliOutputFormat.MARKDOWN.wireValue());

        assertEquals("ask", CliPermissionMode.ASK.wireValue());
        assertEquals("read-only", CliPermissionMode.READ_ONLY.wireValue());
        assertEquals("plan", CliPermissionMode.PLAN.wireValue());
        assertEquals("edit", CliPermissionMode.EDIT.wireValue());
        assertEquals("full", CliPermissionMode.FULL.wireValue());

        assertEquals("error", CliEventSeverity.ERROR.wireValue());
        assertEquals("restricted", ProjectTrustState.RESTRICTED.wireValue());
        assertEquals("allow_once", PermissionDecisionKind.ALLOW_ONCE.wireValue());
        assertEquals("critical", PermissionRisk.CRITICAL.wireValue());
        assertEquals("conflicted", PatchStatus.CONFLICTED.wireValue());
        assertEquals("failed", ToolExecutionStatus.FAILED.wireValue());
        assertEquals("completed", RunStatus.COMPLETED.wireValue());
        assertEquals("tool.completed", CliEventType.TOOL_COMPLETED.wireValue());
    }

    @Test
    void shouldSerializePublicEnumsUsingWireValues() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        assertWireEnum(objectMapper, CliEventType.RUN_COMPLETED, "run.completed", CliEventType.class);
        assertWireEnum(objectMapper, CliOutputFormat.JSON, "json", CliOutputFormat.class);
        assertWireEnum(objectMapper, CliPermissionMode.READ_ONLY, "read-only", CliPermissionMode.class);
        assertWireEnum(objectMapper, CliEventSeverity.INFO, "info", CliEventSeverity.class);
        assertWireEnum(objectMapper, ProjectTrustState.NEVER_TRUST, "never-trust", ProjectTrustState.class);
        assertWireEnum(objectMapper, PermissionDecisionKind.ALLOW_ONCE, "allow_once", PermissionDecisionKind.class);
        assertWireEnum(objectMapper, PermissionRisk.CRITICAL, "critical", PermissionRisk.class);
        assertWireEnum(objectMapper, PatchStatus.CONFLICTED, "conflicted", PatchStatus.class);
        assertWireEnum(objectMapper, RunStatus.COMPLETED, "completed", RunStatus.class);
        assertWireEnum(objectMapper, ToolExecutionStatus.FAILED, "failed", ToolExecutionStatus.class);
    }

    @Test
    void shouldDefaultUntrustedProjectToRestrictedReadOnlyTrust() {
        ProjectIdentity project = new ProjectIdentity(
                "project-1",
                "/workspace/repo",
                "/workspace/repo",
                ProjectTrustState.UNTRUSTED,
                null,
                List.of());

        ProjectTrust trust = ProjectTrust.defaultForUntrustedProject(project);

        assertEquals(ProjectTrustState.RESTRICTED, trust.state());
        assertFalse(trust.trusted());
        assertTrue(trust.readOnly());
        assertEquals(List.of("filesystem.read"), trust.scopes());
    }

    @Test
    void shouldExposeDocumentedCliEventTaxonomy() {
        List<String> eventTypes = Arrays.stream(CliEventType.values())
                .map(CliEventType::wireValue)
                .toList();

        assertTrue(eventTypes.containsAll(List.of(
                "run.started",
                "run.title.updated",
                "assistant.delta",
                "assistant.message.completed",
                "plan.updated",
                "context.budget.updated",
                "context.hygiene.reported",
                "memory.pack.loaded",
                "rag.results.loaded",
                "model.selected",
                "tool.requested",
                "tool.permission.requested",
                "tool.started",
                "tool.output.delta",
                "tool.completed",
                "patch.proposed",
                "patch.applied",
                "lsp.diagnostics.updated",
                "terminal.session.started",
                "run.cancelled",
                "run.completed",
                "run.failed")), eventTypes.stream().collect(Collectors.joining(", ")));
    }

    @Test
    void shouldSerializeCliEventWithReplaySafeEnvelope() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        Instant now = Instant.parse("2026-04-28T10:15:30Z");

        CliEvent event = new CliEvent(
                "cli-event/v1",
                "evt-1",
                7,
                CliEventType.RUN_COMPLETED,
                "run-1",
                "session-1",
                "project-1",
                "trace-1",
                "evt-parent",
                "request-1",
                now,
                CliEventSeverity.INFO,
                Map.of("message", "done"));

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(event));

        assertEquals("cli-event/v1", root.get("schemaVersion").asText());
        assertEquals("evt-1", root.get("eventId").asText());
        assertEquals(7L, root.get("sequence").asLong());
        assertEquals("run.completed", root.get("type").asText());
        assertEquals("run-1", root.get("runId").asText());
        assertEquals("session-1", root.get("sessionId").asText());
        assertEquals("project-1", root.get("projectId").asText());
        assertEquals("trace-1", root.get("traceId").asText());
        assertEquals("evt-parent", root.get("parentEventId").asText());
        assertEquals("request-1", root.get("correlationId").asText());
        assertEquals("2026-04-28T10:15:30Z", root.get("timestamp").asText());
        assertEquals("info", root.get("severity").asText());
        assertEquals("done", root.get("payload").get("message").asText());
        assertEquals(event, objectMapper.treeToValue(root, CliEvent.class));
    }

    @Test
    void shouldRejectInvalidCliEventEnvelope() {
        Instant now = Instant.parse("2026-04-28T10:15:30Z");

        assertThrows(IllegalArgumentException.class, () -> new CliEvent(
                null,
                "evt-1",
                0,
                CliEventType.RUN_STARTED,
                "run-1",
                "session-1",
                "project-1",
                "trace-1",
                null,
                null,
                now,
                CliEventSeverity.INFO,
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new CliEvent(
                "cli-event/v1",
                " ",
                0,
                CliEventType.RUN_STARTED,
                "run-1",
                "session-1",
                "project-1",
                "trace-1",
                null,
                null,
                now,
                CliEventSeverity.INFO,
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new CliEvent(
                "cli-event/v1",
                "evt-1",
                -1,
                CliEventType.RUN_STARTED,
                "run-1",
                "session-1",
                "project-1",
                "trace-1",
                null,
                null,
                now,
                CliEventSeverity.INFO,
                Map.of()));
    }

    @Test
    void shouldSerializeCliDtosWithStableJsonShape() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        Instant now = Instant.parse("2026-04-28T10:15:30Z");
        ProjectIdentity project = new ProjectIdentity(
                "project-1",
                "/workspace/golemcore",
                "/workspace/golemcore",
                ProjectTrustState.TRUSTED,
                "/workspace/golemcore/.golemcore/config.json",
                List.of("/workspace/golemcore/.golemcore/rules/GOLEM.md"));
        CliEvent event = new CliEvent(
                "cli-event/v1",
                "evt-1",
                1,
                CliEventType.RUN_COMPLETED,
                "run-1",
                "session-1",
                "project-1",
                "trace-1",
                null,
                "request-1",
                now,
                CliEventSeverity.INFO,
                Map.of("message", "done"));

        JsonNode invocation = jsonNode(objectMapper, new CliInvocation(
                "golemcore-bot",
                List.of("cli", "run"),
                Map.of("TERM", "xterm-256color"),
                "/workspace/golemcore",
                now));
        assertFieldNames(invocation, "executable", "arguments", "environment", "workingDirectory", "startedAt");
        assertEquals("2026-04-28T10:15:30Z", invocation.get("startedAt").asText());

        JsonNode projectNode = jsonNode(objectMapper, project);
        assertFieldNames(projectNode, "projectId", "rootPath", "gitRoot", "trustState", "configPath", "rulesFiles");
        assertEquals("trusted", projectNode.get("trustState").asText());

        JsonNode trust = jsonNode(objectMapper, new ProjectTrust(
                project,
                ProjectTrustState.TRUSTED,
                now,
                "alice",
                List.of("filesystem.read")));
        assertFieldNames(trust, "project", "state", "grantedAt", "grantedBy", "scopes");
        assertEquals("2026-04-28T10:15:30Z", trust.get("grantedAt").asText());

        JsonNode session = jsonNode(objectMapper, new CliSessionRef(
                "session-1",
                "project-1",
                "Session",
                now,
                now));
        assertFieldNames(session, "sessionId", "projectId", "title", "createdAt", "lastUsedAt");
        assertEquals("2026-04-28T10:15:30Z", session.get("createdAt").asText());
        assertEquals("2026-04-28T10:15:30Z", session.get("lastUsedAt").asText());

        JsonNode profile = jsonNode(objectMapper, new AgentProfile(
                "code-reviewer",
                "Code Reviewer",
                "Reviews code",
                "primary",
                null,
                "coding",
                List.of("code-review"),
                List.of("github"),
                Map.of("filesystem", "read-write"),
                Map.of("default", "ask"),
                true,
                true,
                true,
                "markdown",
                "Prompt"));
        assertFieldNames(profile, "id", "name", "description", "mode", "model", "tier", "skills", "mcp", "tools",
                "permissions", "memoryRead", "memoryWrite", "ragRead", "defaultOutputFormat", "prompt");

        JsonNode budget = jsonNode(objectMapper, new RunBudget(4, 8, "PT5M"));
        assertFieldNames(budget, "maxLlmCalls", "maxToolExecutions", "timeout");

        JsonNode request = jsonNode(objectMapper, new RunRequest(
                "request-1",
                "Run tests",
                List.of("build.gradle"),
                List.of("@git:status"),
                "session-1",
                "coding",
                "coding",
                null,
                new RunBudget(4, 8, "PT5M"),
                CliPermissionMode.READ_ONLY,
                CliOutputFormat.JSON,
                Map.of("traceId", "trace-1")));
        assertFieldNames(request, "requestId", "prompt", "files", "contextRefs", "sessionId", "agentId", "tier",
                "model", "budget", "permissionMode", "outputFormat", "metadata");
        assertEquals("read-only", request.get("permissionMode").asText());
        assertEquals("json", request.get("outputFormat").asText());

        JsonNode permissionRequest = jsonNode(objectMapper, new PermissionRequest(
                "permission-1",
                "run-1",
                "shell.execute",
                "Run tests",
                PermissionRisk.MEDIUM,
                Map.of("command", "mvn test"),
                List.of("cmd:mvn test"),
                List.of("allow_once", "deny"),
                null));
        assertFieldNames(permissionRequest, "requestId", "runId", "tool", "argsSummary", "risk", "argsPreview",
                "scopes", "choices", "diffPreview");
        assertEquals("medium", permissionRequest.get("risk").asText());

        JsonNode permissionDecision = jsonNode(objectMapper, new PermissionDecision(
                "permission-1",
                PermissionDecisionKind.ALLOW_ONCE,
                Duration.ofMinutes(5),
                List.of("cmd:mvn test"),
                "approved"));
        assertFieldNames(permissionDecision, "requestId", "decision", "duration", "scopes", "reason");
        assertEquals("allow_once", permissionDecision.get("decision").asText());
        assertEquals("PT5M", permissionDecision.get("duration").asText());

        JsonNode patch = jsonNode(objectMapper, new PatchSet(
                "patch-1",
                List.of("A.java"),
                List.of("@@ -1 +1 @@"),
                "run-1",
                PatchStatus.PROPOSED));
        assertFieldNames(patch, "patchId", "files", "hunks", "authorRunId", "status");
        assertEquals("proposed", patch.get("status").asText());

        JsonNode snapshot = jsonNode(objectMapper, new WorkspaceSnapshot(
                "snapshot-1",
                "abc123",
                Map.of("A.java", "sha256:abc"),
                List.of("patch-1")));
        assertFieldNames(snapshot, "snapshotId", "gitRef", "fileHashes", "patchRefs");

        JsonNode diagnostics = jsonNode(objectMapper, new LspDiagnosticPack(
                "java",
                "A.java",
                List.of(new LspDiagnosticPack.Diagnostic(
                        "A.java",
                        12,
                        8,
                        CliEventSeverity.WARN,
                        "compiler.warning",
                        "unchecked operation")),
                "1"));
        assertFieldNames(diagnostics, "language", "file", "diagnostics", "version");
        assertEquals("warn", diagnostics.get("diagnostics").get(0).get("severity").asText());

        JsonNode contextBudget = jsonNode(objectMapper, new ContextBudgetReport(
                10000,
                2400,
                7600,
                List.of(new ContextBudgetReport.Section("prompt", 2400))));
        assertFieldNames(contextBudget, "maxTokens", "usedTokens", "remainingTokens", "sections");

        JsonNode toolExecution = jsonNode(objectMapper, new ToolExecutionRecord(
                "shell.execute",
                "mvn test",
                ToolExecutionStatus.COMPLETED,
                Duration.ofMillis(250),
                "ok",
                Map.of("TOKEN", "redacted"),
                Map.of("exitCode", 0)));
        assertFieldNames(toolExecution, "tool", "argsSummary", "status", "duration", "outputSummary",
                "sensitiveRedactions", "metadata");
        assertEquals("completed", toolExecution.get("status").asText());
        assertEquals("PT0.25S", toolExecution.get("duration").asText());

        JsonNode result = jsonNode(objectMapper, new RunResult(
                "session-1",
                "run-1",
                "trace-1",
                RunStatus.COMPLETED,
                "Done",
                Map.of("inputTokens", 1234),
                Map.of("memoryPersisted", true),
                CliExitCode.SUCCESS,
                List.of(event),
                now));
        assertFieldNames(result, "sessionId", "runId", "traceId", "status", "finalResponse", "usage",
                "persistenceOutcome", "exitCode", "events", "completedAt");
        assertEquals("completed", result.get("status").asText());
        assertEquals("2026-04-28T10:15:30Z", result.get("completedAt").asText());
        assertEquals("run.completed", result.get("events").get(0).get("type").asText());
    }

    @Test
    void shouldMatchGoldenCliEventNdjsonFixture() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        Instant now = Instant.parse("2026-04-28T10:15:30Z");
        List<CliEvent> events = List.of(
                new CliEvent(
                        "cli-event/v1",
                        "evt-1",
                        1,
                        CliEventType.RUN_STARTED,
                        "run-1",
                        "session-1",
                        "project-1",
                        "trace-1",
                        null,
                        "request-1",
                        now,
                        CliEventSeverity.INFO,
                        Map.of("prompt", "Run tests")),
                new CliEvent(
                        "cli-event/v1",
                        "evt-2",
                        2,
                        CliEventType.RUN_COMPLETED,
                        "run-1",
                        "session-1",
                        "project-1",
                        "trace-1",
                        "evt-1",
                        "request-1",
                        now,
                        CliEventSeverity.INFO,
                        Map.of("status", "completed")));
        String actual = objectMapper.writeValueAsString(events.get(0)) + "\n"
                + objectMapper.writeValueAsString(events.get(1)) + "\n";

        String expected;
        try (InputStream fixture = getClass().getResourceAsStream("/golden/cli/run-events.ndjson")) {
            assertNotNull(fixture, "golden fixture must exist");
            expected = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertEquals(expected, actual);
    }

    @Test
    void shouldModelFirstCliContractSlice() {
        Instant now = Instant.parse("2026-04-28T10:15:30Z");
        ProjectIdentity project = new ProjectIdentity(
                "project-1",
                "/workspace/golemcore",
                "/workspace/golemcore",
                ProjectTrustState.TRUSTED,
                "/workspace/golemcore/.golemcore/config.json",
                List.of("/workspace/golemcore/.golemcore/rules/GOLEM.md"));
        ProjectTrust trust = new ProjectTrust(project, ProjectTrustState.TRUSTED, now, "alice",
                List.of("filesystem.read"));
        CliInvocation invocation = new CliInvocation(
                "golemcore-bot",
                List.of("exec", "task"),
                Map.of("TERM", "xterm-256color"),
                "/workspace/golemcore",
                now);
        RunBudget runBudget = new RunBudget(4, 8, "PT5M");
        RunRequest request = new RunRequest(
                "request-1",
                "Implement the CLI contracts slice",
                List.of("src/App.java"),
                List.of("@diagnostics"),
                "session-1",
                "coding",
                "coding",
                "openai/gpt-5.2",
                runBudget,
                CliPermissionMode.ASK,
                CliOutputFormat.JSON,
                Map.of("traceId", "trace-1"));
        AgentProfile agentProfile = new AgentProfile(
                "code-reviewer",
                "Code Reviewer",
                "Reviews code for production risk",
                "primary",
                null,
                "coding",
                List.of("code-review"),
                List.of("github"),
                Map.of("filesystem", "read-write"),
                Map.of("default", "ask"),
                true,
                true,
                true,
                "markdown",
                "System prompt");
        PermissionRequest permissionRequest = new PermissionRequest(
                "permission-1",
                "run-1",
                "shell.execute",
                "Run mvn test",
                PermissionRisk.MEDIUM,
                Map.of("command", "mvn test"),
                List.of("cmd:mvn test"),
                List.of("allow_once", "allow_session", "deny", "edit"),
                null);
        PermissionDecision permissionDecision = new PermissionDecision(
                "permission-1",
                PermissionDecisionKind.ALLOW_ONCE,
                Duration.ofMinutes(5),
                List.of("cmd:mvn test"),
                "tests are allowed");
        PatchSet patchSet = new PatchSet(
                "patch-1",
                List.of("A.java"),
                List.of("@@ -1 +1 @@"),
                "run-1",
                PatchStatus.PROPOSED);
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot(
                "snapshot-1",
                "abc123",
                Map.of("A.java", "sha256:abc"),
                List.of("patch-1"));
        LspDiagnosticPack.Diagnostic diagnostic = new LspDiagnosticPack.Diagnostic(
                "A.java",
                12,
                8,
                CliEventSeverity.WARN,
                "compiler.warning",
                "unchecked operation");
        LspDiagnosticPack diagnostics = new LspDiagnosticPack(
                "java",
                "A.java",
                List.of(diagnostic),
                "1");
        ContextBudgetReport.Section promptSection = new ContextBudgetReport.Section("prompt", 2400);
        ContextBudgetReport budget = new ContextBudgetReport(10000, 2400, 7600, List.of(promptSection));
        ToolExecutionRecord toolExecution = new ToolExecutionRecord(
                "shell.execute",
                "mvn test",
                ToolExecutionStatus.COMPLETED,
                Duration.ofMillis(250),
                "ok",
                Map.of("TOKEN", "redacted"),
                Map.of("exitCode", 0));
        CliEvent event = new CliEvent(
                CliEventType.TOOL_COMPLETED,
                "run-1",
                "session-1",
                "trace-1",
                now,
                CliEventSeverity.INFO,
                Map.of("tool", toolExecution.tool()));
        RunResult result = new RunResult(
                "session-1",
                "run-1",
                "trace-1",
                RunStatus.COMPLETED,
                "Done",
                Map.of("inputTokens", 1234),
                Map.of("memoryPersisted", true),
                CliExitCode.SUCCESS,
                List.of(event),
                now);

        assertEquals(project, trust.project());
        assertTrue(trust.trusted());
        assertEquals(List.of("exec", "task"), invocation.arguments());
        assertEquals(CliPermissionMode.ASK, request.permissionMode());
        assertEquals(List.of("src/App.java"), request.files());
        assertEquals("coding", agentProfile.tier());
        assertEquals("shell.execute", permissionRequest.tool());
        assertEquals(PermissionDecisionKind.ALLOW_ONCE, permissionDecision.decision());
        assertEquals(List.of("A.java"), patchSet.files());
        assertEquals("sha256:abc", snapshot.fileHashes().get("A.java"));
        assertEquals(diagnostic, diagnostics.diagnostics().get(0));
        assertEquals(promptSection, budget.sections().get(0));
        assertEquals(Duration.ofMillis(250), toolExecution.duration());
        assertEquals(event, result.events().get(0));
    }

    @Test
    void shouldDefensivelyCopyCollectionsAndUseEmptyDefaults() {
        Instant now = Instant.parse("2026-04-28T10:15:30Z");
        CliSessionRef session = new CliSessionRef("session-1", "project-1", "Session", now, now);

        List<String> arguments = new ArrayList<>(List.of("run"));
        Map<String, String> environment = new HashMap<>(Map.of("A", "B"));
        CliInvocation invocation = new CliInvocation("golemcore-bot", arguments, environment, "/workspace", now);
        arguments.add("later");
        environment.put("C", "D");

        assertEquals(List.of("run"), invocation.arguments());
        assertEquals(Map.of("A", "B"), invocation.environment());
        assertThrows(UnsupportedOperationException.class, () -> invocation.arguments().add("blocked"));
        assertThrows(UnsupportedOperationException.class, () -> invocation.environment().put("blocked", "true"));

        CliEvent event = new CliEvent(
                CliEventType.ASSISTANT_MESSAGE_COMPLETED,
                "run-1",
                session.sessionId(),
                "trace-1",
                now,
                CliEventSeverity.INFO,
                null);
        PermissionRequest permissionRequest = new PermissionRequest(
                "permission-1",
                "run-1",
                "shell.execute",
                "Run command",
                PermissionRisk.LOW,
                null,
                null,
                null,
                null);
        RunResult result = new RunResult(
                "session-1",
                "run-1",
                "trace-1",
                RunStatus.COMPLETED,
                "Done",
                null,
                null,
                CliExitCode.SUCCESS,
                null,
                now);

        assertTrue(event.payload().isEmpty());
        assertTrue(permissionRequest.argsPreview().isEmpty());
        assertTrue(permissionRequest.scopes().isEmpty());
        assertTrue(result.events().isEmpty());
        assertTrue(new PatchSet("patch-1", null, null, "run-1", PatchStatus.PROPOSED).files().isEmpty());
        assertTrue(new WorkspaceSnapshot("snapshot-1", null, null, null).patchRefs().isEmpty());
        assertTrue(new LspDiagnosticPack("java", "A.java", null, "1").diagnostics().isEmpty());
        assertTrue(new ContextBudgetReport(100, 20, 80, null).sections().isEmpty());
        assertTrue(new ToolExecutionRecord(
                "shell.execute",
                "mvn test",
                ToolExecutionStatus.COMPLETED,
                Duration.ZERO,
                "",
                null,
                null).metadata().isEmpty());
    }

    @Test
    void shouldRoundTripThroughJacksonAsDtoContracts() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        Instant now = Instant.parse("2026-04-28T10:15:30Z");
        ProjectIdentity project = new ProjectIdentity(
                "project-1",
                "/workspace/golemcore",
                "/workspace/golemcore",
                ProjectTrustState.TRUSTED,
                "/workspace/golemcore/.golemcore/config.json",
                List.of("/workspace/golemcore/.golemcore/rules/GOLEM.md"));
        CliEvent event = new CliEvent(
                CliEventType.RUN_COMPLETED,
                "run-1",
                "session-1",
                "trace-1",
                now,
                CliEventSeverity.INFO,
                Map.of("message", "done"));
        RunRequest request = new RunRequest(
                "request-1",
                "Run tests",
                List.of("build.gradle"),
                List.of("@git:status"),
                "session-1",
                "coding",
                "coding",
                null,
                new RunBudget(4, 8, "PT5M"),
                CliPermissionMode.READ_ONLY,
                CliOutputFormat.JSON,
                Map.of("traceId", "trace-1"));

        assertJsonRoundTrip(objectMapper, new CliInvocation(
                "golemcore-bot",
                List.of("cli", "run"),
                Map.of("TERM", "xterm-256color"),
                "/workspace/golemcore",
                now), CliInvocation.class);
        assertJsonRoundTrip(objectMapper, project, ProjectIdentity.class);
        assertJsonRoundTrip(objectMapper, new ProjectTrust(
                project,
                ProjectTrustState.TRUSTED,
                now,
                "alice",
                List.of("filesystem.read")), ProjectTrust.class);
        assertJsonRoundTrip(objectMapper, new CliSessionRef(
                "session-1",
                "project-1",
                "Session",
                now,
                now), CliSessionRef.class);
        assertJsonRoundTrip(objectMapper, new AgentProfile(
                "code-reviewer",
                "Code Reviewer",
                "Reviews code",
                "primary",
                null,
                "coding",
                List.of("code-review"),
                List.of("github"),
                Map.of("filesystem", "read-write"),
                Map.of("default", "ask"),
                true,
                true,
                true,
                "markdown",
                "Prompt"), AgentProfile.class);
        assertJsonRoundTrip(objectMapper, new RunBudget(4, 8, "PT5M"), RunBudget.class);
        assertJsonRoundTrip(objectMapper, request, RunRequest.class);
        assertJsonRoundTrip(objectMapper, event, CliEvent.class);
        assertJsonRoundTrip(objectMapper, new PermissionRequest(
                "permission-1",
                "run-1",
                "shell.execute",
                "Run tests",
                PermissionRisk.MEDIUM,
                Map.of("command", "mvn test"),
                List.of("cmd:mvn test"),
                List.of("allow_once", "deny"),
                null), PermissionRequest.class);
        assertJsonRoundTrip(objectMapper, new PermissionDecision(
                "permission-1",
                PermissionDecisionKind.ALLOW_ONCE,
                Duration.ofMinutes(5),
                List.of("cmd:mvn test"),
                "approved"), PermissionDecision.class);
        assertJsonRoundTrip(objectMapper, new PatchSet(
                "patch-1",
                List.of("A.java"),
                List.of("@@ -1 +1 @@"),
                "run-1",
                PatchStatus.PROPOSED), PatchSet.class);
        assertJsonRoundTrip(objectMapper, new WorkspaceSnapshot(
                "snapshot-1",
                "abc123",
                Map.of("A.java", "sha256:abc"),
                List.of("patch-1")), WorkspaceSnapshot.class);
        assertJsonRoundTrip(objectMapper, new LspDiagnosticPack(
                "java",
                "A.java",
                List.of(new LspDiagnosticPack.Diagnostic(
                        "A.java",
                        12,
                        8,
                        CliEventSeverity.WARN,
                        "compiler.warning",
                        "unchecked operation")),
                "1"), LspDiagnosticPack.class);
        assertJsonRoundTrip(objectMapper, new ContextBudgetReport(
                10000,
                2400,
                7600,
                List.of(new ContextBudgetReport.Section("prompt", 2400))), ContextBudgetReport.class);
        assertJsonRoundTrip(objectMapper, new ToolExecutionRecord(
                "shell.execute",
                "mvn test",
                ToolExecutionStatus.COMPLETED,
                Duration.ofMillis(250),
                "ok",
                Map.of("TOKEN", "redacted"),
                Map.of("exitCode", 0)), ToolExecutionRecord.class);
        assertJsonRoundTrip(objectMapper, new RunResult(
                "session-1",
                "run-1",
                "trace-1",
                RunStatus.COMPLETED,
                "Done",
                Map.of("inputTokens", 1234),
                Map.of("memoryPersisted", true),
                CliExitCode.SUCCESS,
                List.of(event),
                now), RunResult.class);
    }

    @Test
    void shouldRemainPlainContractsWithoutSpringStereotypesOrAgentContext() {
        List<Class<?>> contractTypes = List.of(
                CliExitCode.class,
                CliOutputFormat.class,
                CliPermissionMode.class,
                CliEventSeverity.class,
                CliEventType.class,
                ProjectTrustState.class,
                PermissionDecisionKind.class,
                PermissionRisk.class,
                PatchStatus.class,
                RunStatus.class,
                ToolExecutionStatus.class,
                CliEvent.class,
                CliInvocation.class,
                ProjectIdentity.class,
                ProjectTrust.class,
                CliSessionRef.class,
                AgentProfile.class,
                RunBudget.class,
                RunRequest.class,
                RunResult.class,
                PermissionRequest.class,
                PermissionDecision.class,
                PatchSet.class,
                WorkspaceSnapshot.class,
                LspDiagnosticPack.class,
                LspDiagnosticPack.Diagnostic.class,
                ContextBudgetReport.class,
                ContextBudgetReport.Section.class,
                ToolExecutionRecord.class);

        for (Class<?> contractType : contractTypes) {
            assertTrue(contractType.isEnum() || contractType.isRecord(), contractType.getName());
            assertFalse(hasSpringStereotype(contractType), contractType.getName());
            assertFalse(exposesAgentContext(contractType), contractType.getName());
        }
    }

    private static boolean hasSpringStereotype(Class<?> contractType) {
        return Arrays.stream(contractType.getAnnotations())
                .map(annotation -> annotation.annotationType().getName())
                .anyMatch(annotationType -> annotationType.startsWith("org.springframework.stereotype."));
    }

    private static final String AGENT_CONTEXT_TYPE = "me.golemcore.bot.domain.model.AgentContext";

    private static boolean exposesAgentContext(Class<?> contractType) {
        if (contractType.isRecord()) {
            return Arrays.stream(contractType.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .anyMatch(AGENT_CONTEXT_TYPE::equals);
        }
        return Arrays.stream(contractType.getDeclaredFields())
                .map(field -> field.getType().getName())
                .anyMatch(AGENT_CONTEXT_TYPE::equals);
    }

    private static <T> void assertJsonRoundTrip(ObjectMapper objectMapper, T value, Class<T> type) throws Exception {
        String json = objectMapper.writeValueAsString(value);
        assertEquals(value, objectMapper.readValue(json, type), type.getName());
    }

    private static ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }

    private static JsonNode jsonNode(ObjectMapper objectMapper, Object value) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(value));
    }

    private static void assertFieldNames(JsonNode node, String... expectedFieldNames) {
        List<String> actualFieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(actualFieldNames::add);
        assertEquals(List.of(expectedFieldNames), actualFieldNames);
    }

    private static <E extends Enum<E>> void assertWireEnum(
            ObjectMapper objectMapper, E value, String wireValue, Class<E> type) throws Exception {
        assertEquals("\"" + wireValue + "\"", objectMapper.writeValueAsString(value), type.getName());
        assertEquals(value, objectMapper.readValue("\"" + wireValue + "\"", type), type.getName());
        assertThrows(Exception.class, () -> objectMapper.readValue("\"" + value.name() + "\"", type), type.getName());
    }
}
