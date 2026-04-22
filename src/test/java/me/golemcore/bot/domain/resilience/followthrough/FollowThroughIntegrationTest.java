package me.golemcore.bot.domain.resilience.followthrough;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.adapter.outbound.trace.JacksonTraceSnapshotCodecAdapter;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.InternalTurnService;
import me.golemcore.bot.domain.service.TraceBudgetService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.TraceSnapshotCompressionService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.system.FollowThroughSystem;
import me.golemcore.bot.port.outbound.InboundMessageDispatchPort;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration for the follow-through resilience layer.
 *
 * <p>
 * Wires the real {@link FollowThroughSystem}, {@link FollowThroughClassifier},
 * {@link FollowThroughPromptBuilder}, {@link FollowThroughVerdictParser} (with
 * the real {@link JacksonTraceSnapshotCodecAdapter}) and the real
 * {@link InternalTurnService}. The LLM and the inbound dispatch port are the
 * only seams that are mocked.
 */
class FollowThroughIntegrationTest {

    private static final String USER_TEXT = "please gather the three files";
    private static final String ASSISTANT_TEXT = "I'll now gather the files.";
    private static final String SAFE_CONTINUATION = "Continue with the concrete next action you just promised, using only the latest user request and visible conversation context. Do not broaden scope. If the action is destructive, asks for credentials, sends external messages, modifies production, or is ambiguous, ask the real user for confirmation.";
    private static final String MODEL_TIER = "routing";
    private static final String MODEL_ID = "test/router-model";
    private static final String SESSION_ID = "session-1";
    private static final String CHAT_ID = "chat-1";

    private Clock clock;
    private LlmPort llmPort;
    private ModelSelectionService modelSelectionService;
    private RuntimeConfigService runtimeConfigService;
    private TraceService traceService;
    private InboundMessageDispatchPort inboundMessageDispatchPort;
    private FollowThroughSystem system;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-19T12:00:00Z"), ZoneOffset.UTC);
        llmPort = mock(LlmPort.class);
        modelSelectionService = mock(ModelSelectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        traceService = new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService());

        when(modelSelectionService.resolveExplicitTier(MODEL_TIER))
                .thenReturn(new ModelSelectionService.ModelSelection(MODEL_ID, null));
        when(runtimeConfigService.isFollowThroughEnabled()).thenReturn(true);
        when(runtimeConfigService.getFollowThroughConfig()).thenReturn(
                RuntimeConfig.FollowThroughConfig.builder()
                        .enabled(true)
                        .modelTier(MODEL_TIER)
                        .timeoutSeconds(5)
                        .maxChainDepth(1)
                        .build());

        FollowThroughPromptBuilder promptBuilder = new FollowThroughPromptBuilder();
        FollowThroughVerdictParser verdictParser = new FollowThroughVerdictParser(
                new JacksonTraceSnapshotCodecAdapter(new ObjectMapper().findAndRegisterModules()));
        FollowThroughClassifier classifier = new FollowThroughClassifier(
                llmPort, modelSelectionService, promptBuilder, verdictParser);
        InternalTurnService internalTurnService = new InternalTurnService(inboundMessageDispatchPort, clock);
        system = new FollowThroughSystem(classifier, internalTurnService, runtimeConfigService, traceService,
                new JacksonTraceSnapshotCodecAdapter(new ObjectMapper().findAndRegisterModules()), clock);
    }

    @Test
    void shouldDispatchNudgeEndToEndWhenClassifierReturnsUnfulfilledCommitmentJson() {
        stubLlmResponse("""
                {"intent_type":"commitment","has_unfulfilled_commitment":true,
                 "commitment_category":"read_files",
                 "risk_level":"low",
                 "commitment_text":"gather the three files",
                 "continuation_prompt":"%s",
                 "reason":"committed but no tool invoked"}
                """.formatted("malicious prompt ignored"));
        AgentContext context = contextWith(userMessage(USER_TEXT, 0L, null),
                assistantMessage(ASSISTANT_TEXT));

        system.process(context);

        Message dispatched = captureDispatched();
        assertEquals("user", dispatched.getRole());
        assertEquals(SAFE_CONTINUATION, dispatched.getContent());
        assertEquals(CHAT_ID, dispatched.getChatId());
        assertEquals("telegram", dispatched.getChannelType());
        assertEquals("internal:follow-through", dispatched.getSenderId());
        assertEquals(clock.instant(), dispatched.getTimestamp());

        Map<String, Object> metadata = dispatched.getMetadata();
        assertNotNull(metadata);
        assertEquals(Boolean.TRUE, metadata.get(ContextAttributes.MESSAGE_INTERNAL));
        assertEquals(ContextAttributes.MESSAGE_INTERNAL_KIND_FOLLOW_THROUGH_NUDGE,
                metadata.get(ContextAttributes.MESSAGE_INTERNAL_KIND));
        assertEquals(ContextAttributes.TURN_QUEUE_KIND_INTERNAL_FOLLOW_THROUGH,
                metadata.get(ContextAttributes.TURN_QUEUE_KIND));
        assertEquals(1, metadata.get(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_CHAIN_DEPTH));
        assertEquals(0L, metadata.get(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE));
        assertTrue(Boolean.TRUE.equals(
                context.getAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED)));

        LlmRequest sentRequest = captureLlmRequest();
        assertEquals(MODEL_ID, sentRequest.getModel());
        assertEquals(MODEL_TIER, sentRequest.getModelTier());
        assertTrue(sentRequest.getSystemPrompt().contains("follow-through classifier"));
    }

    @Test
    void shouldNotDispatchAnyNudgeWhenClassifierClassifiesReplyAsOptionsOffered() {
        stubLlmResponse("""
                {"intent_type":"options_offered","has_unfulfilled_commitment":false,
                 "commitment_category":"unknown","risk_level":"low",
                 "commitment_text":null,"continuation_prompt":null,
                 "reason":"assistant waited for user choice"}
                """);
        AgentContext context = contextWith(userMessage(USER_TEXT, 0L, null),
                assistantMessage("We could either A, B, or C — which would you prefer?"));

        system.process(context);

        verifyNoInteractions(inboundMessageDispatchPort);
        assertFalse(Boolean.TRUE.equals(
                context.getAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED)));
    }

    @Test
    void shouldNotDispatchNudgeWhenClassifierReturnsHighRiskCommitmentJson() {
        stubLlmResponse("""
                {"intent_type":"commitment","has_unfulfilled_commitment":true,
                 "commitment_category":"run_tests","risk_level":"high",
                 "commitment_text":"deploy to production",
                 "reason":"production action"}
                """);
        AgentContext context = contextWith(userMessage(USER_TEXT, 0L, null),
                assistantMessage(ASSISTANT_TEXT));

        system.process(context);

        verifyNoInteractions(inboundMessageDispatchPort);
        assertFalse(Boolean.TRUE.equals(
                context.getAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED)));
    }

    @Test
    void shouldStopNudgingWhenInboundUserMessageAlreadyAtMaxChainDepth() {
        Map<String, Object> inboundMetadata = new LinkedHashMap<>();
        inboundMetadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        inboundMetadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND,
                ContextAttributes.MESSAGE_INTERNAL_KIND_FOLLOW_THROUGH_NUDGE);
        inboundMetadata.put(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_CHAIN_DEPTH, 1);

        AgentContext context = contextWith(userMessage(SAFE_CONTINUATION, 0L, inboundMetadata),
                assistantMessage("Still committing but not yet calling the tool."));

        assertFalse(system.shouldProcess(context));
        system.process(context);

        verify(llmPort, never()).chat(any(LlmRequest.class));
        verifyNoInteractions(inboundMessageDispatchPort);
    }

    @Test
    void shouldNotDispatchNudgeWhenLlmTimesOutSoClassifierFailsClosed() {
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(new CompletableFuture<>());
        when(runtimeConfigService.getFollowThroughConfig()).thenReturn(
                RuntimeConfig.FollowThroughConfig.builder()
                        .enabled(true)
                        .modelTier(MODEL_TIER)
                        .timeoutSeconds(0)
                        .maxChainDepth(1)
                        .build());
        AgentContext context = contextWith(userMessage(USER_TEXT, 0L, null),
                assistantMessage(ASSISTANT_TEXT));

        system.process(context);

        verifyNoInteractions(inboundMessageDispatchPort);
        assertFalse(Boolean.TRUE.equals(
                context.getAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED)));
    }

    @Test
    void shouldSkipDispatchWhenStopArrivesWhileClassifierIsInFlight() throws Exception {
        CompletableFuture<LlmResponse> llmFuture = new CompletableFuture<>();
        CountDownLatch classifierEntered = new CountDownLatch(1);
        when(llmPort.chat(any(LlmRequest.class))).thenAnswer(invocation -> {
            classifierEntered.countDown();
            return llmFuture;
        });

        AgentContext context = contextWith(userMessage(USER_TEXT, 0L, null),
                assistantMessage(ASSISTANT_TEXT));
        Map<String, Object> sessionMetadata = new LinkedHashMap<>();
        context.getSession().setMetadata(sessionMetadata);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<AgentContext> processFuture = executor.submit(() -> system.process(context));

            assertTrue(classifierEntered.await(5, TimeUnit.SECONDS),
                    "classifier must enter the LLM call so we can race /stop against it");

            sessionMetadata.put(ContextAttributes.TURN_INTERRUPT_REQUESTED, true);

            llmFuture.complete(LlmResponse.builder().content("""
                    {"intent_type":"commitment","has_unfulfilled_commitment":true,
                     "commitment_category":"read_files",
                     "risk_level":"low",
                     "commitment_text":"gather the three files",
                     "continuation_prompt":"%s",
                     "reason":"committed but no tool invoked"}
                    """.formatted("malicious prompt ignored")).build());

            processFuture.get(5, TimeUnit.SECONDS);
        }

        verifyNoInteractions(inboundMessageDispatchPort);
        assertFalse(Boolean.TRUE.equals(
                context.getAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED)),
                "stop arriving mid-classifier must not leave the turn marked as scheduled");
    }

    private void stubLlmResponse(String rawJson) {
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(
                CompletableFuture.completedFuture(LlmResponse.builder().content(rawJson).build()));
    }

    private Message captureDispatched() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(inboundMessageDispatchPort).dispatch(captor.capture());
        return captor.getValue();
    }

    private LlmRequest captureLlmRequest() {
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(captor.capture());
        return captor.getValue();
    }

    private AgentContext contextWith(Message... messages) {
        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .channelType("telegram")
                .chatId(CHAT_ID)
                .messages(new ArrayList<>())
                .build();
        List<Message> list = new ArrayList<>(List.of(messages));
        return AgentContext.builder().session(session).messages(list).build();
    }

    private Message userMessage(String content, long activitySequence, Map<String, Object> metadata) {
        Map<String, Object> effectiveMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            effectiveMetadata.putAll(metadata);
        }
        effectiveMetadata.put(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE, activitySequence);
        return Message.builder()
                .id("u-" + content.hashCode())
                .role("user")
                .content(content)
                .metadata(effectiveMetadata)
                .channelType("telegram")
                .chatId(CHAT_ID)
                .senderId("u1")
                .build();
    }

    private Message assistantMessage(String content) {
        return Message.builder()
                .id("a-" + content.hashCode())
                .role("assistant")
                .content(content)
                .channelType("telegram")
                .chatId(CHAT_ID)
                .senderId("assistant")
                .build();
    }
}
