package me.golemcore.bot.domain.model;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentContextTest {

    private static final String TOOL_CALL_ID = "tc1";

    @Test
    void addToolResultShouldInitializeMapWhenNull() {
        AgentContext ctx = AgentContext.builder().toolResults(null).build();

        ctx.addToolResult(TOOL_CALL_ID, ToolResult.success("ok"));

        assertNotNull(ctx.getToolResults());
        assertEquals("ok", ctx.getToolResults().get(TOOL_CALL_ID).getOutput());
    }

    @Test
    void setAttributeShouldInitializeMapWhenNull() {
        AgentContext ctx = AgentContext.builder().attributes(null).build();

        ctx.setAttribute("k", "v");

        assertNotNull(ctx.getAttributes());
        assertEquals("v", ctx.getAttribute("k"));
    }

    @Test
    void getAttributeShouldReturnNullWhenAttributesNull() {
        AgentContext ctx = AgentContext.builder().attributes(null).build();
        assertNull(ctx.getAttribute("missing"));
    }

    @Test
    void clearSkillTransitionRequestShouldSetNull() {
        SkillTransitionRequest req = SkillTransitionRequest.pipeline("next-skill");
        AgentContext ctx = AgentContext.builder().skillTransitionRequest(req).build();

        assertNotNull(ctx.getSkillTransitionRequest());
        ctx.clearSkillTransitionRequest();
        assertNull(ctx.getSkillTransitionRequest());
    }

    @Test
    void finalAnswerReadyDefaultShouldBeFalseAndCanBeSet() {
        AgentContext ctx = AgentContext.builder().build();
        assertFalse(Boolean.TRUE.equals(ctx.getAttribute(ContextAttributes.FINAL_ANSWER_READY)));

        ctx.setAttribute(ContextAttributes.FINAL_ANSWER_READY, true);
        assertTrue(Boolean.TRUE.equals(ctx.getAttribute(ContextAttributes.FINAL_ANSWER_READY)));
    }

    @Test
    void builderDefaultsShouldInitializeCollections() {
        AgentContext ctx = AgentContext.builder().build();

        assertNotNull(ctx.getMessages());
        assertNotNull(ctx.getAvailableTools());
        assertNotNull(ctx.getActiveSkills());
        assertNotNull(ctx.getToolResults());
        assertNotNull(ctx.getAttributes());

        // Ensure they are mutable collections (we rely on mutation in systems)
        ctx.getAttributes().put("k", "v");
        assertEquals("v", ctx.getAttribute("k"));

        ctx.getToolResults().put("tc", ToolResult.success("ok"));
        assertEquals("ok", ctx.getToolResults().get("tc").getOutput());
    }

    @Test
    void builderShouldRespectProvidedMaps() {
        AgentContext ctx = AgentContext.builder()
                .attributes(new java.util.HashMap<>(Map.of("a", 1)))
                .toolResults(new java.util.HashMap<>(Map.of(TOOL_CALL_ID, ToolResult.success("ok"))))
                .build();

        assertEquals(1, ctx.<Integer>getAttribute("a"));
        assertEquals("ok", ctx.getToolResults().get(TOOL_CALL_ID).getOutput());
    }

    // ==================== TurnOutcome typed field ====================

    @Test
    void turnOutcomeDefaultShouldBeNull() {
        AgentContext ctx = AgentContext.builder().build();
        assertNull(ctx.getTurnOutcome());
    }

    @Test
    void turnOutcomeShouldBeSettableAndGettable() {
        AgentContext ctx = AgentContext.builder().build();
        TurnOutcome outcome = TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .assistantText("hello")
                .build();

        ctx.setTurnOutcome(outcome);

        assertNotNull(ctx.getTurnOutcome());
        assertEquals(FinishReason.SUCCESS, ctx.getTurnOutcome().getFinishReason());
        assertEquals("hello", ctx.getTurnOutcome().getAssistantText());
    }

    // ==================== Failures accumulation ====================

    @Test
    void failuresDefaultShouldBeEmpty() {
        AgentContext ctx = AgentContext.builder().build();
        assertNotNull(ctx.getFailures());
        assertTrue(ctx.getFailures().isEmpty());
    }

    @Test
    void addFailureShouldAccumulate() {
        AgentContext ctx = AgentContext.builder().build();
        FailureEvent f1 = new FailureEvent(
                FailureSource.SYSTEM, "SystemA", FailureKind.EXCEPTION, "err1", Instant.now());
        FailureEvent f2 = new FailureEvent(
                FailureSource.LLM, "Adapter", FailureKind.TIMEOUT, "err2", Instant.now());

        ctx.addFailure(f1);
        ctx.addFailure(f2);

        assertEquals(2, ctx.getFailures().size());
        assertEquals(f1, ctx.getFailures().get(0));
        assertEquals(f2, ctx.getFailures().get(1));
    }

    @Test
    void addFailureShouldInitializeListWhenNull() {
        AgentContext ctx = AgentContext.builder().failures(null).build();
        FailureEvent failure = new FailureEvent(
                FailureSource.TOOL, "ShellTool", FailureKind.VALIDATION, "bad", Instant.now());

        ctx.addFailure(failure);

        assertEquals(1, ctx.getFailures().size());
    }

    @Test
    void getFailuresShouldReturnUnmodifiableView() {
        AgentContext ctx = AgentContext.builder().build();
        ctx.addFailure(new FailureEvent(
                FailureSource.SYSTEM, "S", FailureKind.UNKNOWN, "x", Instant.now()));

        assertThrows(UnsupportedOperationException.class, () -> ctx.getFailures().add(new FailureEvent(
                FailureSource.LLM, "L", FailureKind.EXCEPTION, "y", Instant.now())));
    }
}
