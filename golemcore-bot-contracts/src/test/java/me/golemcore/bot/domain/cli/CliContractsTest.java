package me.golemcore.bot.domain.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CliContractsTest {

    @Test
    void shouldExposeStableEnumValues() {
        assertEquals(0, CliExitCode.SUCCESS.processCode());
        assertEquals(1, CliExitCode.GENERAL_ERROR.processCode());
        assertEquals(2, CliExitCode.INVALID_USAGE.processCode());
        assertEquals(5, CliExitCode.PERMISSION_DENIED.processCode());
        assertEquals(8, CliExitCode.TIMEOUT.processCode());
        assertEquals(130, CliExitCode.CANCELLED.processCode());
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
        CliSessionRef session = new CliSessionRef("session-1", "project-1", "CLI design", now, now);
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
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
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

        String json = objectMapper.writeValueAsString(request);
        RunRequest restored = objectMapper.readValue(json, RunRequest.class);

        assertEquals(request, restored);
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

    private static boolean exposesAgentContext(Class<?> contractType) {
        if (contractType.isRecord()) {
            return Arrays.stream(contractType.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .anyMatch(typeName -> typeName.equals("me.golemcore.bot.domain.model.AgentContext"));
        }
        return Arrays.stream(contractType.getDeclaredFields())
                .map(field -> field.getType().getName())
                .anyMatch(typeName -> typeName.equals("me.golemcore.bot.domain.model.AgentContext"));
    }
}
