package me.golemcore.bot.domain.system;

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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.TurnOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TurnOutcomeFinalizationSystemTest {

    private TurnOutcomeFinalizationSystem system;

    @BeforeEach
    void setUp() {
        system = new TurnOutcomeFinalizationSystem();
    }

    // ==================== Identity ====================

    @Test
    void nameAndOrder() {
        assertEquals("TurnOutcomeFinalizationSystem", system.getName());
        assertEquals(57, system.getOrder());
    }

    // ==================== shouldProcess ====================

    @Test
    void shouldNotProcessWhenTurnOutcomeAlreadySet() {
        AgentContext context = AgentContext.builder().build();
        context.setTurnOutcome(TurnOutcome.builder().finishReason(FinishReason.SUCCESS).build());

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenFinalAnswerReady() {
        AgentContext context = AgentContext.builder().finalAnswerReady(true).build();

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenLlmErrorSet() {
        AgentContext context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.LLM_ERROR, "something failed");

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenOutgoingResponseSet() {
        AgentContext context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("hello"));

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenNothingReady() {
        AgentContext context = AgentContext.builder().build();

        assertFalse(system.shouldProcess(context));
    }

    // ==================== FinishReason paths ====================

    @Test
    void shouldSetSuccessFinishReasonWhenFinalAnswerReady() {
        AgentContext context = AgentContext.builder()
                .finalAnswerReady(true)
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("hello").build());

        system.process(context);

        assertNotNull(context.getTurnOutcome());
        assertEquals(FinishReason.SUCCESS, context.getTurnOutcome().getFinishReason());
    }

    @Test
    void shouldSetErrorFinishReasonWhenLlmErrorSet() {
        AgentContext context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.LLM_ERROR, "API failure");

        system.process(context);

        assertNotNull(context.getTurnOutcome());
        assertEquals(FinishReason.ERROR, context.getTurnOutcome().getFinishReason());
    }

    @Test
    void shouldSetIterationLimitFinishReason() {
        AgentContext context = AgentContext.builder()
                .finalAnswerReady(true)
                .build();
        context.setAttribute(ContextAttributes.ITERATION_LIMIT_REACHED, true);

        system.process(context);

        assertEquals(FinishReason.ITERATION_LIMIT, context.getTurnOutcome().getFinishReason());
    }

    @Test
    void shouldSetPlanModeFinishReason() {
        AgentContext context = AgentContext.builder()
                .finalAnswerReady(true)
                .build();
        context.setAttribute(ContextAttributes.PLAN_APPROVAL_NEEDED, "plan-123");

        system.process(context);

        assertEquals(FinishReason.PLAN_MODE, context.getTurnOutcome().getFinishReason());
    }

    @Test
    void iterationLimitTakesPriorityOverLlmError() {
        AgentContext context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.ITERATION_LIMIT_REACHED, true);
        context.setAttribute(ContextAttributes.LLM_ERROR, "also error");

        system.process(context);

        assertEquals(FinishReason.ITERATION_LIMIT, context.getTurnOutcome().getFinishReason());
    }

    @Test
    void llmErrorTakesPriorityOverPlanMode() {
        AgentContext context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.LLM_ERROR, "error");
        context.setAttribute(ContextAttributes.PLAN_APPROVAL_NEEDED, "plan-456");

        system.process(context);

        assertEquals(FinishReason.ERROR, context.getTurnOutcome().getFinishReason());
    }

    // ==================== assistantText extraction ====================

    @Test
    void shouldExtractAssistantTextFromLlmResponse() {
        AgentContext context = AgentContext.builder()
                .finalAnswerReady(true)
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("Hello world").build());

        system.process(context);

        assertEquals("Hello world", context.getTurnOutcome().getAssistantText());
    }

    @Test
    void shouldReturnNullAssistantTextWhenNoLlmResponse() {
        AgentContext context = AgentContext.builder()
                .finalAnswerReady(true)
                .build();

        system.process(context);

        assertNull(context.getTurnOutcome().getAssistantText());
    }

    @Test
    void shouldReturnNullAssistantTextWhenLlmResponseContentIsNull() {
        AgentContext context = AgentContext.builder()
                .finalAnswerReady(true)
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(null).build());

        system.process(context);

        assertNull(context.getTurnOutcome().getAssistantText());
    }

    // ==================== outgoingResponse propagation ====================

    @Test
    void shouldPropagateOutgoingResponse() {
        AgentContext context = AgentContext.builder()
                .finalAnswerReady(true)
                .build();
        OutgoingResponse outgoing = OutgoingResponse.textOnly("hi");
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, outgoing);

        system.process(context);

        assertSame(outgoing, context.getTurnOutcome().getOutgoingResponse());
    }

    // ==================== model propagation ====================

    @Test
    void shouldPropagateModel() {
        AgentContext context = AgentContext.builder()
                .finalAnswerReady(true)
                .build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "gpt-4");

        system.process(context);

        assertEquals("gpt-4", context.getTurnOutcome().getModel());
    }

    // ==================== autoMode detection ====================

    @Test
    void shouldDetectAutoMode() {
        Message autoMsg = Message.builder()
                .role("user")
                .content("auto task")
                .timestamp(Instant.now())
                .metadata(Map.of("auto.mode", true))
                .build();
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>(List.of(autoMsg)))
                .finalAnswerReady(true)
                .build();

        system.process(context);

        assertTrue(context.getTurnOutcome().isAutoMode());
    }

    @Test
    void shouldDetectNonAutoMode() {
        Message msg = Message.builder()
                .role("user")
                .content("normal task")
                .timestamp(Instant.now())
                .build();
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>(List.of(msg)))
                .finalAnswerReady(true)
                .build();

        system.process(context);

        assertFalse(context.getTurnOutcome().isAutoMode());
    }

    @Test
    void shouldHandleEmptyMessagesForAutoMode() {
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>())
                .finalAnswerReady(true)
                .build();

        system.process(context);

        assertFalse(context.getTurnOutcome().isAutoMode());
    }

    // ==================== failures propagation ====================

    @Test
    void shouldPropagateFailures() {
        AgentContext context = AgentContext.builder()
                .finalAnswerReady(true)
                .build();
        FailureEvent failure = new FailureEvent(
                FailureSource.SYSTEM, "TestSystem", FailureKind.EXCEPTION, "boom", Instant.now());
        context.addFailure(failure);

        system.process(context);

        assertEquals(1, context.getTurnOutcome().getFailures().size());
        assertEquals(failure, context.getTurnOutcome().getFailures().get(0));
    }

    // ==================== idempotency ====================

    @Test
    void shouldNotOverwriteExistingTurnOutcome() {
        TurnOutcome existing = TurnOutcome.builder().finishReason(FinishReason.ERROR).build();
        AgentContext context = AgentContext.builder()
                .finalAnswerReady(true)
                .build();
        context.setTurnOutcome(existing);

        system.process(context);

        assertSame(existing, context.getTurnOutcome());
    }

    // ==================== fallback FinishReason ====================

    @Test
    void shouldFallbackToSuccessWhenOnlyOutgoingResponsePresent() {
        AgentContext context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("fallback"));

        system.process(context);

        assertEquals(FinishReason.SUCCESS, context.getTurnOutcome().getFinishReason());
    }
}
