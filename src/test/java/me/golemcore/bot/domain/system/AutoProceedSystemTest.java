package me.golemcore.bot.domain.system;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.resilience.autoproceed.AutoProceedClassifier;
import me.golemcore.bot.domain.resilience.autoproceed.ClassifierRequest;
import me.golemcore.bot.domain.resilience.autoproceed.ClassifierVerdict;
import me.golemcore.bot.domain.resilience.autoproceed.IntentType;
import me.golemcore.bot.domain.service.InternalTurnService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.InboundMessageDispatchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AutoProceedSystemTest {

    private static final String USER_TEXT = "review the changes";
    private static final String ASSISTANT_TEXT = "I'm ready to run the tests. Shall I proceed?";
    private static final String AFFIRMATION = "Yes, please proceed.";

    private AutoProceedClassifier classifier;
    private InternalTurnService internalTurnService;
    private RuntimeConfigService runtimeConfigService;
    private AutoProceedSystem system;

    @BeforeEach
    void setUp() {
        classifier = mock(AutoProceedClassifier.class);
        internalTurnService = mock(InternalTurnService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isAutoProceedEnabled()).thenReturn(true);
        when(runtimeConfigService.getAutoProceedConfig()).thenReturn(
                RuntimeConfig.AutoProceedConfig.builder()
                        .enabled(true)
                        .modelTier("routing")
                        .timeoutSeconds(5)
                        .maxChainDepth(2)
                        .build());
        when(internalTurnService.scheduleAutoProceedAffirmation(any(), anyString(), anyInt())).thenReturn(true);
        system = new AutoProceedSystem(classifier, internalTurnService, runtimeConfigService);
    }

    @Test
    void shouldDispatchAffirmationWhenClassifierDetectsRhetoricalConfirm() {
        when(classifier.classify(any(ClassifierRequest.class), eq("routing"), eq(Duration.ofSeconds(5))))
                .thenReturn(ClassifierVerdict.affirm("shall I proceed", AFFIRMATION, "single forward path"));

        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        system.process(context);

        verify(internalTurnService).scheduleAutoProceedAffirmation(context, AFFIRMATION, 0);
        assertTrue((Boolean) context.getAttribute(ContextAttributes.RESILIENCE_AUTO_PROCEED_SCHEDULED));
    }

    @Test
    void shouldSkipWhenAutoProceedDisabled() {
        when(runtimeConfigService.isAutoProceedEnabled()).thenReturn(false);
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        assertFalse(system.shouldProcess(context));
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleAutoProceedAffirmation(any(), anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenFollowThroughAlreadyScheduledNudgeInSameTurn() {
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());
        context.setAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED, true);

        assertFalse(system.shouldProcess(context),
                "auto-proceed must not stack on top of a follow-through nudge in the same turn");
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleAutoProceedAffirmation(any(), anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenToolsExecutedSinceLastUserMessage() {
        AgentContext context = contextWith(
                userMessage(USER_TEXT, null),
                assistantMessage("calling tool...", true),
                toolMessage("read_file"),
                assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        assertFalse(system.shouldProcess(context));
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleAutoProceedAffirmation(any(), anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenLlmErrorIsRecorded() {
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_ERROR, "boom");

        assertFalse(system.shouldProcess(context));
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleAutoProceedAffirmation(any(), anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenPlanModeActive() {
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());
        context.setAttribute(ContextAttributes.PLAN_MODE_ACTIVE, true);

        assertFalse(system.shouldProcess(context));
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleAutoProceedAffirmation(any(), anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenFinalAssistantTextIsBlank() {
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage("", false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder().content("").build());

        assertFalse(system.shouldProcess(context));
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleAutoProceedAffirmation(any(), anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenVerdictIsNotActionable() {
        when(classifier.classify(any(ClassifierRequest.class), anyString(), any(Duration.class)))
                .thenReturn(ClassifierVerdict.nonActionable(IntentType.CHOICE_REQUEST, "offered options"));
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        system.process(context);

        verify(classifier, times(1)).classify(any(), anyString(), any(Duration.class));
        verify(internalTurnService, never()).scheduleAutoProceedAffirmation(any(), anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenIncomingChainDepthReachesMax() {
        Map<String, Object> inboundMetadata = new LinkedHashMap<>();
        inboundMetadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        inboundMetadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND,
                ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_PROCEED);
        inboundMetadata.put(ContextAttributes.RESILIENCE_AUTO_PROCEED_CHAIN_DEPTH, 2);

        AgentContext context = contextWith(
                userMessage(AFFIRMATION, inboundMetadata),
                assistantMessage("still asking to confirm", false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("still asking to confirm").build());

        assertFalse(system.shouldProcess(context));
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleAutoProceedAffirmation(any(), anyString(), anyInt());
    }

    @Test
    void shouldReportStableNameAndPostFollowThroughOrder() {
        assertEquals("AutoProceedSystem", system.getName());
        assertEquals(62, system.getOrder());
    }

    @Test
    void shouldSkipWhenLastUserMessageMetadataMarksAutoMode() {
        Map<String, Object> autoMeta = new LinkedHashMap<>();
        autoMeta.put(ContextAttributes.AUTO_MODE, true);
        AgentContext context = contextWith(userMessage(USER_TEXT, autoMeta),
                assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        assertFalse(system.shouldProcess(context));
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleAutoProceedAffirmation(any(), anyString(), anyInt());
    }

    @Test
    void shouldNotThrowOrMarkScheduledWhenInternalTurnDispatchFailsClosed() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        doThrow(new IllegalStateException("queue unavailable"))
                .when(inboundMessageDispatchPort).dispatch(any(Message.class));
        InternalTurnService failClosedInternalTurnService = new InternalTurnService(
                inboundMessageDispatchPort,
                Clock.fixed(Instant.parse("2026-04-21T03:00:00Z"), ZoneOffset.UTC));
        AutoProceedSystem failClosedSystem = new AutoProceedSystem(
                classifier, failClosedInternalTurnService, runtimeConfigService);
        when(classifier.classify(any(ClassifierRequest.class), anyString(), any(Duration.class)))
                .thenReturn(ClassifierVerdict.affirm("shall I proceed", AFFIRMATION, "single forward path"));
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        assertDoesNotThrow(() -> failClosedSystem.process(context));

        verify(inboundMessageDispatchPort).dispatch(any(Message.class));
        assertNull(context.getAttribute(ContextAttributes.RESILIENCE_AUTO_PROCEED_SCHEDULED),
                "failed dispatch must not mark the turn as scheduled");
    }

    @Test
    void shouldNotSetScheduledAttributeWhenDispatchReturnsFalse() {
        when(internalTurnService.scheduleAutoProceedAffirmation(any(), anyString(), anyInt())).thenReturn(false);
        when(classifier.classify(any(ClassifierRequest.class), anyString(), any(Duration.class)))
                .thenReturn(ClassifierVerdict.affirm("shall I proceed", AFFIRMATION, "single forward path"));
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        system.process(context);

        verify(internalTurnService).scheduleAutoProceedAffirmation(context, AFFIRMATION, 0);
        assertNull(context.getAttribute(ContextAttributes.RESILIENCE_AUTO_PROCEED_SCHEDULED),
                "failed dispatch must not mark the turn as scheduled");
    }

    @Test
    void shouldSkipClassifierWhenTurnAlreadyHasInterruptRequestedOnContext() {
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());
        context.setAttribute(ContextAttributes.TURN_INTERRUPT_REQUESTED, true);

        assertFalse(system.shouldProcess(context),
                "classifier must not run when /stop already marked this turn as interrupt-requested");
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleAutoProceedAffirmation(any(), anyString(), anyInt());
    }

    @Test
    void shouldSkipClassifierWhenSessionMetadataHasInterruptRequested() {
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());
        Map<String, Object> sessionMetadata = new LinkedHashMap<>();
        sessionMetadata.put(ContextAttributes.TURN_INTERRUPT_REQUESTED, true);
        context.getSession().setMetadata(sessionMetadata);

        assertFalse(system.shouldProcess(context),
                "classifier must not run when the session carries a pending /stop request");
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleAutoProceedAffirmation(any(), anyString(), anyInt());
    }

    @Test
    void shouldNotDispatchAffirmationWhenStopArrivesBetweenClassifierAndDispatch() {
        Map<String, Object> sessionMetadata = new LinkedHashMap<>();
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());
        context.getSession().setMetadata(sessionMetadata);

        when(classifier.classify(any(ClassifierRequest.class), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    sessionMetadata.put(ContextAttributes.TURN_INTERRUPT_REQUESTED, true);
                    return ClassifierVerdict.affirm("shall I proceed", AFFIRMATION, "single forward path");
                });

        system.process(context);

        verify(classifier, times(1)).classify(any(), anyString(), any(Duration.class));
        verify(internalTurnService, never()).scheduleAutoProceedAffirmation(any(), anyString(), anyInt());
        assertNull(context.getAttribute(ContextAttributes.RESILIENCE_AUTO_PROCEED_SCHEDULED),
                "stop arriving mid-classifier must not leave the turn marked as scheduled");
    }

    @Test
    void shouldBuildClassifierRequestFromUserAndAssistantTextWithEmptyToolListWhenNoToolsRan() {
        when(classifier.classify(any(ClassifierRequest.class), anyString(), any(Duration.class)))
                .thenReturn(ClassifierVerdict.affirm("shall I proceed", AFFIRMATION, "ok"));
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        system.process(context);

        ArgumentCaptor<ClassifierRequest> captor = ArgumentCaptor.forClass(ClassifierRequest.class);
        verify(classifier).classify(captor.capture(), anyString(), any(Duration.class));
        ClassifierRequest sent = captor.getValue();
        assertEquals(USER_TEXT, sent.userMessage());
        assertEquals(ASSISTANT_TEXT, sent.assistantReply());
        assertTrue(sent.executedToolsInTurn().isEmpty());
    }

    private AgentContext contextWith(Message... messages) {
        AgentSession session = AgentSession.builder()
                .id("session-1")
                .channelType("telegram")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        List<Message> list = new ArrayList<>(List.of(messages));
        return AgentContext.builder().session(session).messages(list).build();
    }

    private Message userMessage(String content, Map<String, Object> metadata) {
        return Message.builder()
                .id("u-" + content.hashCode())
                .role("user")
                .content(content)
                .metadata(metadata != null ? metadata : new LinkedHashMap<>())
                .build();
    }

    private Message assistantMessage(String content, boolean withToolCalls) {
        Message.MessageBuilder builder = Message.builder()
                .id("a-" + content.hashCode())
                .role("assistant")
                .content(content);
        if (withToolCalls) {
            builder.toolCalls(List.of(Message.ToolCall.builder()
                    .id("tc-1")
                    .name("read_file")
                    .arguments(Map.of())
                    .build()));
        }
        return builder.build();
    }

    private Message toolMessage(String toolName) {
        return Message.builder()
                .id("t-" + toolName)
                .role("tool")
                .content("{}")
                .toolName(toolName)
                .build();
    }
}
