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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TurnOutcomeTest {

    // ==================== TurnOutcome builder ====================

    @Test
    void shouldBuildWithDefaults() {
        TurnOutcome outcome = TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .build();

        assertEquals(FinishReason.SUCCESS, outcome.getFinishReason());
        assertNull(outcome.getAssistantText());
        assertNull(outcome.getOutgoingResponse());
        assertNull(outcome.getModel());
        assertNull(outcome.getRoutingOutcome());
        assertFalse(outcome.isRawHistoryWritten());
        assertFalse(outcome.isAutoMode());
        assertNotNull(outcome.getFailures());
        assertTrue(outcome.getFailures().isEmpty());
    }

    @Test
    void shouldBuildWithAllFields() {
        OutgoingResponse outgoing = OutgoingResponse.textOnly("hello");
        RoutingOutcome routing = RoutingOutcome.builder().attempted(true).sentText(true).build();
        FailureEvent failure = new FailureEvent(
                FailureSource.SYSTEM, "TestSystem", FailureKind.EXCEPTION, "boom", Instant.now());

        TurnOutcome outcome = TurnOutcome.builder()
                .finishReason(FinishReason.ERROR)
                .assistantText("hello")
                .outgoingResponse(outgoing)
                .failure(failure)
                .rawHistoryWritten(true)
                .model("gpt-4")
                .autoMode(true)
                .routingOutcome(routing)
                .build();

        assertEquals(FinishReason.ERROR, outcome.getFinishReason());
        assertEquals("hello", outcome.getAssistantText());
        assertSame(outgoing, outcome.getOutgoingResponse());
        assertEquals(1, outcome.getFailures().size());
        assertSame(failure, outcome.getFailures().get(0));
        assertTrue(outcome.isRawHistoryWritten());
        assertEquals("gpt-4", outcome.getModel());
        assertTrue(outcome.isAutoMode());
        assertSame(routing, outcome.getRoutingOutcome());
    }

    @Test
    void shouldAccumulateFailuresViaSingular() {
        FailureEvent f1 = new FailureEvent(FailureSource.LLM, "llm", FailureKind.TIMEOUT, "t1", Instant.now());
        FailureEvent f2 = new FailureEvent(FailureSource.TOOL, "tool", FailureKind.EXCEPTION, "t2", Instant.now());

        TurnOutcome outcome = TurnOutcome.builder()
                .finishReason(FinishReason.ERROR)
                .failure(f1)
                .failure(f2)
                .build();

        assertEquals(2, outcome.getFailures().size());
        assertEquals(f1, outcome.getFailures().get(0));
        assertEquals(f2, outcome.getFailures().get(1));
    }

    @Test
    void shouldAcceptFailuresList() {
        FailureEvent f1 = new FailureEvent(FailureSource.SYSTEM, "s", FailureKind.UNKNOWN, "x", Instant.now());
        List<FailureEvent> list = List.of(f1);

        TurnOutcome outcome = TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .failures(list)
                .build();

        assertEquals(1, outcome.getFailures().size());
    }

    @Test
    void shouldHaveCorrectDefaultsForBooleans() {
        TurnOutcome outcome = TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .build();

        assertFalse(outcome.isRawHistoryWritten());
        assertFalse(outcome.isAutoMode());
    }

    // ==================== RoutingOutcome ====================

    @Test
    void routingOutcomeShouldBuildWithDefaults() {
        RoutingOutcome routing = RoutingOutcome.builder().build();

        assertFalse(routing.isAttempted());
        assertFalse(routing.isSentText());
        assertFalse(routing.isSentVoice());
        assertEquals(0, routing.getSentAttachments());
        assertNull(routing.getErrorMessage());
    }

    @Test
    void routingOutcomeShouldBuildSuccessCase() {
        RoutingOutcome routing = RoutingOutcome.builder()
                .attempted(true)
                .sentText(true)
                .sentVoice(true)
                .sentAttachments(2)
                .build();

        assertTrue(routing.isAttempted());
        assertTrue(routing.isSentText());
        assertTrue(routing.isSentVoice());
        assertEquals(2, routing.getSentAttachments());
        assertNull(routing.getErrorMessage());
    }

    @Test
    void routingOutcomeShouldBuildFailureCase() {
        RoutingOutcome routing = RoutingOutcome.builder()
                .attempted(true)
                .sentText(false)
                .errorMessage("connection refused")
                .build();

        assertTrue(routing.isAttempted());
        assertFalse(routing.isSentText());
        assertEquals("connection refused", routing.getErrorMessage());
    }

    // ==================== FailureEvent record ====================

    @Test
    void failureEventRecordEquality() {
        Instant now = Instant.now();
        FailureEvent f1 = new FailureEvent(FailureSource.SYSTEM, "TestSystem", FailureKind.EXCEPTION, "err", now);
        FailureEvent f2 = new FailureEvent(FailureSource.SYSTEM, "TestSystem", FailureKind.EXCEPTION, "err", now);

        assertEquals(f1, f2);
        assertEquals(f1.hashCode(), f2.hashCode());
    }

    @Test
    void failureEventRecordAccessors() {
        Instant now = Instant.now();
        FailureEvent event = new FailureEvent(FailureSource.LLM, "Adapter", FailureKind.RATE_LIMIT, "429", now);

        assertEquals(FailureSource.LLM, event.source());
        assertEquals("Adapter", event.component());
        assertEquals(FailureKind.RATE_LIMIT, event.kind());
        assertEquals("429", event.message());
        assertEquals(now, event.timestamp());
    }

    @Test
    void failureEventRecordInequality() {
        Instant now = Instant.now();
        FailureEvent f1 = new FailureEvent(FailureSource.SYSTEM, "A", FailureKind.EXCEPTION, "err", now);
        FailureEvent f2 = new FailureEvent(FailureSource.TOOL, "B", FailureKind.TIMEOUT, "other", now);

        assertNotEquals(f1, f2);
    }

    // ==================== FinishReason enum ====================

    @Test
    void finishReasonValuesExist() {
        assertEquals(6, FinishReason.values().length);
        assertNotNull(FinishReason.valueOf("SUCCESS"));
        assertNotNull(FinishReason.valueOf("ERROR"));
        assertNotNull(FinishReason.valueOf("ITERATION_LIMIT"));
        assertNotNull(FinishReason.valueOf("DEADLINE"));
        assertNotNull(FinishReason.valueOf("POLICY_DENIED"));
        assertNotNull(FinishReason.valueOf("PLAN_MODE"));
    }

    // ==================== FailureSource / FailureKind enums ====================

    @Test
    void failureSourceValuesExist() {
        assertEquals(4, FailureSource.values().length);
    }

    @Test
    void failureKindValuesExist() {
        assertEquals(6, FailureKind.values().length);
    }
}
