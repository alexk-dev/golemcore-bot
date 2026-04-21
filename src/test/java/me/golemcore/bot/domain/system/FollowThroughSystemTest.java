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
import me.golemcore.bot.domain.resilience.followthrough.ClassifierRequest;
import me.golemcore.bot.domain.resilience.followthrough.ClassifierVerdict;
import me.golemcore.bot.domain.resilience.followthrough.FollowThroughClassifier;
import me.golemcore.bot.domain.resilience.followthrough.IntentType;
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

class FollowThroughSystemTest {

    private static final String USER_TEXT = "please gather the three files";
    private static final String ASSISTANT_TEXT = "I'll now gather the files.";
    private static final String CONTINUATION = "Gather the three files now.";

    private FollowThroughClassifier classifier;
    private InternalTurnService internalTurnService;
    private RuntimeConfigService runtimeConfigService;
    private FollowThroughSystem system;

    @BeforeEach
    void setUp() {
        classifier = mock(FollowThroughClassifier.class);
        internalTurnService = mock(InternalTurnService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isFollowThroughEnabled()).thenReturn(true);
        when(runtimeConfigService.getFollowThroughConfig()).thenReturn(
                RuntimeConfig.FollowThroughConfig.builder()
                        .enabled(true)
                        .modelTier("routing")
                        .timeoutSeconds(5)
                        .maxChainDepth(1)
                        .build());
        when(internalTurnService.scheduleFollowThroughNudge(any(), anyString(), anyInt())).thenReturn(true);
        system = new FollowThroughSystem(classifier, internalTurnService, runtimeConfigService);
    }

    @Test
    void shouldDispatchNudgeWhenClassifierDetectsUnfulfilledCommitment() {
        when(classifier.classify(any(ClassifierRequest.class), eq("routing"), eq(Duration.ofSeconds(5))))
                .thenReturn(ClassifierVerdict.unfulfilledCommitment(
                        "gather the files", CONTINUATION, "committed but no tool invoked"));

        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        system.process(context);

        verify(internalTurnService).scheduleFollowThroughNudge(context, CONTINUATION, 0);
        assertTrue((Boolean) context.getAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED));
    }

    @Test
    void shouldSkipWhenFollowThroughDisabled() {
        when(runtimeConfigService.isFollowThroughEnabled()).thenReturn(false);
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        assertFalse(system.shouldProcess(context));
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleFollowThroughNudge(any(), anyString(), anyInt());
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
        verify(internalTurnService, never()).scheduleFollowThroughNudge(any(), anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenLlmErrorIsRecorded() {
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_ERROR, "boom");

        assertFalse(system.shouldProcess(context));
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleFollowThroughNudge(any(), anyString(), anyInt());
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
        verify(internalTurnService, never()).scheduleFollowThroughNudge(any(), anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenFinalAssistantTextIsBlank() {
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage("", false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder().content("").build());

        assertFalse(system.shouldProcess(context));
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleFollowThroughNudge(any(), anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenVerdictIsNotActionable() {
        when(classifier.classify(any(ClassifierRequest.class), anyString(), any(Duration.class)))
                .thenReturn(ClassifierVerdict.nonCommitment(IntentType.OPTIONS_OFFERED, "offered options"));
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        system.process(context);

        verify(classifier, times(1)).classify(any(), anyString(), any(Duration.class));
        verify(internalTurnService, never()).scheduleFollowThroughNudge(any(), anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenIncomingChainDepthReachesMax() {
        Map<String, Object> inboundMetadata = new LinkedHashMap<>();
        inboundMetadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        inboundMetadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND,
                ContextAttributes.MESSAGE_INTERNAL_KIND_FOLLOW_THROUGH_NUDGE);
        inboundMetadata.put(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_CHAIN_DEPTH, 1);

        AgentContext context = contextWith(
                userMessage(CONTINUATION, inboundMetadata),
                assistantMessage("still committing but not acting", false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("still committing but not acting").build());

        assertFalse(system.shouldProcess(context));
        system.process(context);

        verifyNoInteractions(classifier);
        verify(internalTurnService, never()).scheduleFollowThroughNudge(any(), anyString(), anyInt());
    }

    @Test
    void shouldPassExecutedToolNamesToClassifierWhenPresentAfterAssistantCall() {
        when(classifier.classify(any(ClassifierRequest.class), anyString(), any(Duration.class)))
                .thenReturn(ClassifierVerdict.fulfilledCommitment("ok", "already acted"));
        AgentContext context = contextWith(
                userMessage(USER_TEXT, null),
                assistantMessage("working...", true),
                toolMessage("read_file"),
                toolMessage("list_dir"),
                assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        // Tools executed guard kicks in — classifier never consulted.
        system.process(context);
        verifyNoInteractions(classifier);
    }

    @Test
    void shouldReportStableNameAndPostRoutingOrder() {
        assertEquals("FollowThroughSystem", system.getName());
        assertEquals(61, system.getOrder());
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
        verify(internalTurnService, never()).scheduleFollowThroughNudge(any(), anyString(), anyInt());
    }

    @Test
    void shouldNotThrowOrMarkScheduledWhenInternalTurnDispatchFailsClosed() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        doThrow(new IllegalStateException("queue unavailable"))
                .when(inboundMessageDispatchPort).dispatch(any(Message.class));
        InternalTurnService failClosedInternalTurnService = new InternalTurnService(
                inboundMessageDispatchPort,
                Clock.fixed(Instant.parse("2026-04-21T03:00:00Z"), ZoneOffset.UTC));
        FollowThroughSystem failClosedSystem = new FollowThroughSystem(
                classifier, failClosedInternalTurnService, runtimeConfigService);
        when(classifier.classify(any(ClassifierRequest.class), anyString(), any(Duration.class)))
                .thenReturn(ClassifierVerdict.unfulfilledCommitment(
                        "gather the files", CONTINUATION, "committed but no tool invoked"));
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        assertDoesNotThrow(() -> failClosedSystem.process(context));

        verify(inboundMessageDispatchPort).dispatch(any(Message.class));
        assertNull(context.getAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED),
                "failed dispatch must not mark the turn as scheduled");
    }

    @Test
    void shouldNotSetScheduledAttributeWhenDispatchReturnsFalse() {
        when(internalTurnService.scheduleFollowThroughNudge(any(), anyString(), anyInt())).thenReturn(false);
        when(classifier.classify(any(ClassifierRequest.class), anyString(), any(Duration.class)))
                .thenReturn(ClassifierVerdict.unfulfilledCommitment(
                        "gather the files", CONTINUATION, "committed but no tool invoked"));
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        system.process(context);

        verify(internalTurnService).scheduleFollowThroughNudge(context, CONTINUATION, 0);
        assertNull(context.getAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED),
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
        verify(internalTurnService, never()).scheduleFollowThroughNudge(any(), anyString(), anyInt());
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
        verify(internalTurnService, never()).scheduleFollowThroughNudge(any(), anyString(), anyInt());
    }

    @Test
    void shouldNotDispatchNudgeWhenStopArrivesBetweenClassifierAndDispatch() {
        Map<String, Object> sessionMetadata = new LinkedHashMap<>();
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());
        context.getSession().setMetadata(sessionMetadata);

        // Simulate /stop arriving while classifier is in-flight: mutate session
        // metadata during the classify() call so the flag flips mid-process.
        when(classifier.classify(any(ClassifierRequest.class), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    sessionMetadata.put(ContextAttributes.TURN_INTERRUPT_REQUESTED, true);
                    return ClassifierVerdict.unfulfilledCommitment(
                            "gather the files", CONTINUATION, "committed but no tool invoked");
                });

        system.process(context);

        verify(classifier, times(1)).classify(any(), anyString(), any(Duration.class));
        verify(internalTurnService, never()).scheduleFollowThroughNudge(any(), anyString(), anyInt());
        assertNull(context.getAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED),
                "stop arriving mid-classifier must not leave the turn marked as scheduled");
    }

    @Test
    void shouldBuildClassifierRequestFromUserAndAssistantTextWithEmptyToolListWhenNoToolsRan() {
        when(classifier.classify(any(ClassifierRequest.class), anyString(), any(Duration.class)))
                .thenReturn(ClassifierVerdict.unfulfilledCommitment(
                        "gather the files", CONTINUATION, "committed but no tool invoked"));
        AgentContext context = contextWith(userMessage(USER_TEXT, null), assistantMessage(ASSISTANT_TEXT, false));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(ASSISTANT_TEXT).build());

        system.process(context);

        ArgumentCaptor<ClassifierRequest> captor = ArgumentCaptor.forClass(ClassifierRequest.class);
        verify(classifier).classify(captor.capture(), anyString(), any(Duration.class));
        ClassifierRequest sent = captor.getValue();
        assertEquals(USER_TEXT, sent.userMessage());
        assertEquals(ASSISTANT_TEXT, sent.assistantReply());
        assertTrue(sent.executedToolsInTurn().isEmpty(),
                "tools-executed guard trips upstream, so the request carries an empty tool list");
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
