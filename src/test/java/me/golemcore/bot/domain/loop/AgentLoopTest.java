package me.golemcore.bot.domain.loop;

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
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RateLimitResult;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.domain.model.SkillTransitionRequest;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TraceBudgetService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.TraceSnapshotCompressionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.VoiceResponseHandler;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.domain.system.ResponseRoutingSystem;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceEventRecord;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.RateLimitPort;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentLoopTest {

    private static final String CHANNEL_TYPE = "telegram";
    private static final String ATTR_ACTIVE_SKILL_SOURCE = "skill.active.source";
    private static final String FIXED_INSTANT = "2026-02-01T00:00:00Z";
    private static final String ROLE_USER = "user";
    private static final String MSG_GENERIC = "generic";

    @Test
    void shouldResetTypedControlFlagsAndToolResultsBetweenIterations() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");
        when(preferencesService.getMessage(any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        AgentSystem verifier = new AgentSystem() {
            @Override
            public String getName() {
                return "verifier";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                if (context.getCurrentIteration() == 0) {
                    context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, true);
                    context.setSkillTransitionRequest(SkillTransitionRequest.pipeline("next"));
                    context.addToolResult("tc1", ToolResult.success("ok"));
                    context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
                            OutgoingResponse.textOnly("hello"));
                    return context;
                }

                assertFalse(Boolean.TRUE.equals(context.getAttribute(ContextAttributes.FINAL_ANSWER_READY)),
                        "finalAnswerReady must be reset between iterations");
                SkillTransitionRequest transition = context.getSkillTransitionRequest();
                assertNotNull(transition, "skillTransitionRequest must be preserved between iterations");
                assertEquals("next", transition.targetSkill(),
                        "skillTransitionRequest target must survive between iterations");
                assertTrue(context.getToolResults().isEmpty(), "toolResults must be cleared between iterations");
                return context;
            }
        };

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(verifier),
                List.of(channel),
                mockRuntimeConfigService(2),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("hi")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        verify(sessionPort, times(1)).save(session);
    }

    @Test
    void shouldUseTransportChatIdForTypingIndicator() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);
        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("conv-1")
                .messages(new ArrayList<>())
                .build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "conv-1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("hi")
                .channelType(CHANNEL_TYPE)
                .chatId("conv-1")
                .senderId("u1")
                .metadata(Map.of(
                        ContextAttributes.TRANSPORT_CHAT_ID, "transport-99",
                        ContextAttributes.CONVERSATION_KEY, "conv-1"))
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        verify(channel, timeout(2000).atLeastOnce()).showTyping("transport-99");
    }

    @Test
    void shouldPersistSessionIdentityMetadataFromInboundMessage() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);
        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("conv-1")
                .messages(new ArrayList<>())
                .build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "conv-1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("identity bind")
                .channelType(CHANNEL_TYPE)
                .chatId("conv-1")
                .senderId("u1")
                .metadata(Map.of(
                        ContextAttributes.TRANSPORT_CHAT_ID, "transport-42",
                        ContextAttributes.CONVERSATION_KEY, "conv-1"))
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        assertEquals("transport-42", session.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        assertEquals("conv-1", session.getMetadata().get(ContextAttributes.CONVERSATION_KEY));
    }

    @Test
    void shouldStartRootTraceAndPropagateTraceContextIntoAgentContext() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);
        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("conv-1")
                .messages(new ArrayList<>())
                .build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "conv-1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);

        AgentSystem verifier = new AgentSystem() {
            @Override
            public String getName() {
                return "trace-verifier";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                TraceContext traceContext = context.getTraceContext();
                assertNotNull(traceContext);
                assertEquals("trace-1", traceContext.getTraceId());
                assertEquals("span-1", traceContext.getSpanId());
                assertEquals(TraceSpanKind.INGRESS.name(), traceContext.getRootKind());
                assertEquals("trace-1", context.getAttribute(ContextAttributes.TRACE_ID));
                assertEquals("span-1", context.getAttribute(ContextAttributes.TRACE_SPAN_ID));
                assertEquals(TraceSpanKind.INGRESS.name(), context.getAttribute(ContextAttributes.TRACE_ROOT_KIND));
                return context;
            }
        };

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(verifier),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("trace me")
                .channelType(CHANNEL_TYPE)
                .chatId("conv-1")
                .senderId("u1")
                .metadata(Map.of(
                        ContextAttributes.TRACE_ID, "trace-1",
                        ContextAttributes.TRACE_SPAN_ID, "span-1",
                        ContextAttributes.TRACE_ROOT_KIND, TraceSpanKind.INGRESS.name(),
                        ContextAttributes.TRACE_NAME, "telegram.message"))
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        assertEquals(1, session.getTraces().size());
        assertEquals("trace-1", session.getTraces().get(0).getTraceId());
        assertEquals("span-1", session.getTraces().get(0).getRootSpanId());
        assertEquals("telegram.message", session.getTraces().get(0).getTraceName());
    }

    @Test
    void shouldRecordSystemAndSessionSaveChildSpans() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);
        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("conv-1")
                .messages(new ArrayList<>())
                .build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "conv-1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);

        AgentSystem tracedSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "traceable";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("done"));
                return context;
            }
        };

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(tracedSystem),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("trace systems")
                .channelType(CHANNEL_TYPE)
                .chatId("conv-1")
                .senderId("u1")
                .metadata(Map.of(
                        ContextAttributes.TRACE_ID, "trace-1",
                        ContextAttributes.TRACE_SPAN_ID, "span-1",
                        ContextAttributes.TRACE_ROOT_KIND, TraceSpanKind.INGRESS.name(),
                        ContextAttributes.TRACE_NAME, "telegram.message"))
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        TraceRecord trace = session.getTraces().get(0);
        TraceSpanRecord systemSpan = trace.getSpans().stream()
                .filter(span -> "system.traceable".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("span-1", systemSpan.getParentSpanId());
        assertEquals(TraceStatusCode.OK, systemSpan.getStatusCode());

        TraceSpanRecord saveSpan = trace.getSpans().stream()
                .filter(span -> "session.save".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("span-1", saveSpan.getParentSpanId());
        assertEquals(TraceStatusCode.OK, saveSpan.getStatusCode());
    }

    @Test
    void shouldNotSendGenericFallbackDuringSkillTransition() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        AgentSystem transitionSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "transition";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setSkillTransitionRequest(SkillTransitionRequest.pipeline("next"));
                return context;
            }
        };

        AgentLoop loop = new AgentLoop(
                sessionPort,
                rateLimitPort,
                List.of(transitionSystem,
                        new ResponseRoutingSystem(new ChannelRegistry(List.of(channel)), preferencesService,
                                mock(VoiceResponseHandler.class))),
                new ChannelRegistry(List.of(channel)),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock,
                new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService()));

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("pipeline")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        verify(channel, never()).sendMessage(eq("1"), eq(MSG_GENERIC), any());
    }

    @Test
    void shouldSendUnsentLlmResponseAsFeedbackGuarantee() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        AgentSystem system = new AgentSystem() {
            @Override
            public String getName() {
                return "system";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("hello"));
                return context;
            }
        };

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(system,
                        new ResponseRoutingSystem(new ChannelRegistry(List.of(channel)), preferencesService,
                                mock(VoiceResponseHandler.class))),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("hi")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        // Feedback guarantee should route via ResponseRoutingSystem when present.
        verify(channel, atLeastOnce()).sendMessage(eq("1"), eq("hello"), any());
        // NOTE: ResponseRoutingSystem is transport-only. It must not write assistant
        // messages
        // into raw history. (Raw history is owned by the domain execution path.)
    }

    @Test
    void shouldSkipDisabledSystem() {
        // Arrange
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // A system that is disabled — its process() should never be called
        AgentSystem disabledSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "disabledSystem";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                fail("process() must not be called on a disabled system");
                return context;
            }
        };

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(disabledSystem),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("hi")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        // Act
        loop.processMessage(inbound);

        // Assert — session saved (loop completed), disabled system was not invoked
        verify(sessionPort, times(1)).save(session);
    }

    @Test
    void shouldRecordFailureEventOnSystemException() {
        // Arrange
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // A system that throws an exception
        AgentSystem throwingSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "throwingSystem";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                throw new IllegalStateException("boom");
            }
        };

        // A second system that captures failures from context
        List<FailureEvent> capturedFailures = new ArrayList<>();
        AgentSystem inspectorSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "inspector";
            }

            @Override
            public int getOrder() {
                return 99;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                capturedFailures.addAll(context.getFailures());
                return context;
            }
        };

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(throwingSystem, inspectorSystem),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("hi")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        // Act
        loop.processMessage(inbound);

        // Assert
        assertEquals(1, capturedFailures.size());
        FailureEvent failure = capturedFailures.get(0);
        assertEquals(FailureSource.SYSTEM, failure.source());
        assertEquals("throwingSystem", failure.component());
        assertEquals(FailureKind.EXCEPTION, failure.kind());
        assertEquals("boom", failure.message());
    }

    @Test
    void shouldCallShutdownWithoutError() {
        // Arrange
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);
        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        LlmPort llmPort = mock(LlmPort.class);
        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        // Act & Assert — no exception thrown
        assertDoesNotThrow(loop::shutdown);
    }

    @Test
    void shouldCollectErrorsFromFailureEvents() {
        // Arrange
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(eq("system.error.feedback"), any())).thenReturn("Error: interpreted");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(true);
        LlmResponse llmResponse = LlmResponse.builder().content("interpreted error").build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(llmResponse));

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // System that adds a FailureEvent to the context but produces no response
        AgentSystem failureSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "failureAdder";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.addFailure(new FailureEvent(
                        FailureSource.LLM, "testLlm", FailureKind.EXCEPTION,
                        "connection refused", clock.instant()));
                return context;
            }
        };

        ResponseRoutingSystem routingSystem = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(channel)), preferencesService, mock(VoiceResponseHandler.class));

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(failureSystem, routingSystem),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("hi")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        // Act
        loop.processMessage(inbound);

        // Assert — the LLM was called to interpret errors and a response was sent
        verify(llmPort).chat(any());
        verify(channel, atLeastOnce()).sendMessage(eq("1"), eq("Error: interpreted"), any());
    }

    @Test
    void shouldHandleNullMessagesInAutoModeCheck() {
        // Arrange
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // System that nulls out the messages list to test isAutoModeContext safety
        AgentSystem nullMessagesSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "nullMessages";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setMessages(null);
                return context;
            }
        };

        ResponseRoutingSystem routingSystem = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(channel)), preferencesService, mock(VoiceResponseHandler.class));

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(nullMessagesSystem, routingSystem),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("hi")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        // Act & Assert — should not throw NullPointerException on isAutoModeContext
        assertDoesNotThrow(() -> loop.processMessage(inbound));
        verify(sessionPort).save(session);
    }

    @Test
    void shouldTruncateNullInput() {
        // Arrange — build a message with null content and verify the loop handles it
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        // Message with null content — truncate(null, 200) should return "<null>"
        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content(null)
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        // Act & Assert — no NPE from truncate(null, 200) in log.debug line
        assertDoesNotThrow(() -> loop.processMessage(inbound));
        verify(sessionPort).save(session);
    }

    @Test
    void shouldEnsureFeedbackWhenRoutingOutcomeNotSent() {
        // Arrange
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn("generic fallback");
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // System that sets a TurnOutcome with RoutingOutcome sentText=false and no
        // OutgoingResponse
        AgentSystem turnOutcomeSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "turnOutcomeSetter";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                RoutingOutcome routingOutcome = RoutingOutcome.builder()
                        .attempted(true)
                        .sentText(false)
                        .build();
                TurnOutcome outcome = TurnOutcome.builder()
                        .finishReason(FinishReason.ERROR)
                        .routingOutcome(routingOutcome)
                        .build();
                context.setTurnOutcome(outcome);
                return context;
            }
        };

        ResponseRoutingSystem routingSystem = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(channel)), preferencesService, mock(VoiceResponseHandler.class));

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(turnOutcomeSystem, routingSystem),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("hi")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        // Act
        loop.processMessage(inbound);

        // Assert — ensureFeedback should trigger because sentText=false,
        // no unsent LLM response exists, LLM not available, so generic fallback fires
        verify(channel, atLeastOnce()).sendMessage(eq("1"), eq("generic fallback"), any());
    }

    @Test
    void shouldSkipFeedbackWhenTurnOutcomeReportsAttachmentDelivery() {
        // Arrange
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn("generic fallback");
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        AgentSystem turnOutcomeSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "turnOutcomeSetter";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                RoutingOutcome routingOutcome = RoutingOutcome.builder()
                        .attempted(true)
                        .sentText(false)
                        .sentAttachments(1)
                        .build();
                TurnOutcome outcome = TurnOutcome.builder()
                        .finishReason(FinishReason.SUCCESS)
                        .routingOutcome(routingOutcome)
                        .build();
                context.setTurnOutcome(outcome);
                return context;
            }
        };

        ResponseRoutingSystem routingSystem = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(channel)), preferencesService, mock(VoiceResponseHandler.class));

        AgentLoop loop = new AgentLoop(
                sessionPort,
                rateLimitPort,
                List.of(turnOutcomeSystem, routingSystem),
                new ChannelRegistry(List.of(channel)),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock,
                new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService()));

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("hi")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        // Act
        loop.processMessage(inbound);

        // Assert
        verify(channel, never()).sendMessage(eq("1"), eq("generic fallback"), any());
    }

    @Test
    void shouldSkipFeedbackWhenRoutingOutcomeAttributeReportsVoiceDelivery() {
        // Arrange
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn("generic fallback");
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        AgentSystem routingOutcomeAttributeSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "routingOutcomeAttributeSetter";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                RoutingOutcome routingOutcome = RoutingOutcome.builder()
                        .attempted(true)
                        .sentText(false)
                        .sentVoice(true)
                        .build();
                context.setAttribute(ContextAttributes.ROUTING_OUTCOME, routingOutcome);
                return context;
            }
        };

        ResponseRoutingSystem routingSystem = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(channel)), preferencesService, mock(VoiceResponseHandler.class));

        AgentLoop loop = new AgentLoop(
                sessionPort,
                rateLimitPort,
                List.of(routingOutcomeAttributeSystem, routingSystem),
                new ChannelRegistry(List.of(channel)),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock,
                new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService()));

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("hi")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        // Act
        loop.processMessage(inbound);

        // Assert
        verify(channel, never()).sendMessage(eq("1"), eq("generic fallback"), any());
    }

    @Test
    void shouldNotifyUserWhenRateLimited() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.denied(5000, "rate limited"));

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage("system.rate.limit"))
                .thenReturn("Rate limit exceeded. Please wait before sending more messages.");
        LlmPort llmPort = mock(LlmPort.class);
        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);

        AgentLoop loop = createLoop(
                sessionPort, rateLimitPort, List.of(), List.of(channel),
                mockRuntimeConfigService(1), preferencesService, llmPort, clock);

        Message inbound = Message.builder()
                .role(ROLE_USER).content("hi")
                .channelType(CHANNEL_TYPE).chatId("1").senderId("u1")
                .timestamp(clock.instant()).build();

        loop.processMessage(inbound);

        verify(sessionPort, never()).getOrCreate(any(), any());
        verify(sessionPort, never()).save(any());
        verify(channel).sendMessage("1", "Rate limit exceeded. Please wait before sending more messages.");
    }

    @Test
    void shouldBypassRateLimitForAutoMode() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);
        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1").channelType(CHANNEL_TYPE).chatId("1")
                .messages(new ArrayList<>()).build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        AgentLoop loop = createLoop(
                sessionPort, rateLimitPort, List.of(), List.of(channel),
                mockRuntimeConfigService(1), preferencesService, llmPort, clock);

        Map<String, Object> meta = new HashMap<>();
        meta.put("auto.mode", true);
        Message autoMsg = Message.builder()
                .role(ROLE_USER).content("auto task")
                .channelType(CHANNEL_TYPE).chatId("1").senderId("auto")
                .metadata(meta).timestamp(clock.instant()).build();

        loop.processMessage(autoMsg);

        verify(rateLimitPort, never()).tryConsume();
        verify(sessionPort).save(session);
    }

    @Test
    void shouldBypassRateLimitAndRawHistoryForInternalRetryMessage() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);
        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1").channelType(CHANNEL_TYPE).chatId("1")
                .messages(new ArrayList<>()).build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        AgentLoop loop = createLoop(
                sessionPort, rateLimitPort, List.of(), List.of(channel),
                mockRuntimeConfigService(1), preferencesService, llmPort, clock);

        Map<String, Object> meta = new HashMap<>();
        meta.put(ContextAttributes.MESSAGE_INTERNAL, true);
        meta.put(ContextAttributes.MESSAGE_INTERNAL_KIND, ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_CONTINUE);
        Message internalMsg = Message.builder()
                .role(ROLE_USER).content("Continue and finish")
                .channelType(CHANNEL_TYPE).chatId("1").senderId("internal")
                .metadata(meta).timestamp(clock.instant()).build();

        loop.processMessage(internalMsg);

        verify(rateLimitPort, never()).tryConsume();
        assertTrue(session.getMessages().isEmpty());
        verify(sessionPort).save(session);
    }

    @Test
    void shouldExposeInternalRetryMessageOnlyInTurnContext() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);
        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1").channelType(CHANNEL_TYPE).chatId("1")
                .messages(new ArrayList<>()).build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);

        List<Message> observedMessages = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean internalInput = new java.util.concurrent.atomic.AtomicBoolean(false);
        AgentSystem inspector = new AgentSystem() {
            @Override
            public String getName() {
                return "inspector";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                internalInput.set(Boolean.TRUE.equals(context.getAttribute(ContextAttributes.TURN_INPUT_INTERNAL)));
                observedMessages.addAll(context.getMessages());
                return context;
            }
        };

        AgentLoop loop = createLoop(
                sessionPort, rateLimitPort, List.of(inspector), List.of(channel),
                mockRuntimeConfigService(1), preferencesService, llmPort, clock);

        Map<String, Object> meta = new HashMap<>();
        meta.put(ContextAttributes.MESSAGE_INTERNAL, true);
        meta.put(ContextAttributes.MESSAGE_INTERNAL_KIND, ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_CONTINUE);
        Message internalMsg = Message.builder()
                .role(ROLE_USER).content("Continue and finish")
                .channelType(CHANNEL_TYPE).chatId("1").senderId("internal")
                .metadata(meta).timestamp(clock.instant()).build();

        loop.processMessage(internalMsg);

        assertTrue(internalInput.get());
        assertEquals(1, observedMessages.size());
        assertTrue(observedMessages.get(0).isInternalMessage());
        assertEquals("Continue and finish", observedMessages.get(0).getContent());
        assertTrue(session.getMessages().isEmpty());
        verify(rateLimitPort, never()).tryConsume();
    }

    @Test
    void shouldSkipFeedbackGuaranteeWhenInternalRetryAlreadyScheduled() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);
        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1").channelType(CHANNEL_TYPE).chatId("1")
                .messages(new ArrayList<>()).build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        AgentSystem retryScheduled = new AgentSystem() {
            @Override
            public String getName() {
                return "retryScheduled";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setAttribute(ContextAttributes.TURN_INTERNAL_RETRY_SCHEDULED, true);
                return context;
            }
        };

        ResponseRoutingSystem routing = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(channel)), preferencesService, mock(VoiceResponseHandler.class));

        AgentLoop loop = createLoop(
                sessionPort, rateLimitPort, List.of(retryScheduled, routing), List.of(channel),
                mockRuntimeConfigService(1), preferencesService, llmPort, clock);

        Message inbound = Message.builder()
                .role(ROLE_USER).content("hi")
                .channelType(CHANNEL_TYPE).chatId("1").senderId("u1")
                .timestamp(clock.instant()).build();

        loop.processMessage(inbound);

        verify(channel, never()).sendMessage(eq("1"), eq(MSG_GENERIC), any());
    }

    @Test
    void shouldSkipFeedbackGuaranteeForAutoMode() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);
        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1").channelType(CHANNEL_TYPE).chatId("1")
                .messages(new ArrayList<>()).build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        ResponseRoutingSystem routing = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(channel)), preferencesService, mock(VoiceResponseHandler.class));

        AgentLoop loop = createLoop(
                sessionPort, rateLimitPort, List.of(routing), List.of(channel),
                mockRuntimeConfigService(1), preferencesService, llmPort, clock);

        Map<String, Object> meta = new HashMap<>();
        meta.put("auto.mode", true);
        Message autoMsg = Message.builder()
                .role(ROLE_USER).content("auto task")
                .channelType(CHANNEL_TYPE).chatId("1").senderId("auto")
                .metadata(meta).timestamp(clock.instant()).build();

        loop.processMessage(autoMsg);

        verify(channel, never()).sendMessage(eq("1"), eq(MSG_GENERIC), any());
    }

    @Test
    void shouldSendIterationLimitMessage() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(eq("system.iteration.limit"), any())).thenReturn("Limit reached (1)");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);
        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1").channelType(CHANNEL_TYPE).chatId("1")
                .messages(new ArrayList<>()).build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // System that always requests a skill transition → loop never stops naturally
        AgentSystem alwaysTransition = new AgentSystem() {
            @Override
            public String getName() {
                return "alwaysTransition";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setSkillTransitionRequest(SkillTransitionRequest.pipeline("next"));
                return context;
            }
        };

        ResponseRoutingSystem routing = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(channel)), preferencesService, mock(VoiceResponseHandler.class));

        AgentLoop loop = createLoop(
                sessionPort, rateLimitPort, List.of(alwaysTransition, routing), List.of(channel),
                mockRuntimeConfigService(1), preferencesService, llmPort, clock);

        Message inbound = Message.builder()
                .role(ROLE_USER).content("hi")
                .channelType(CHANNEL_TYPE).chatId("1").senderId("u1")
                .timestamp(clock.instant()).build();

        loop.processMessage(inbound);

        verify(channel, atLeastOnce()).sendMessage(eq("1"), eq("Limit reached (1)"), any());
    }

    @Test
    void shouldFallbackWhenOnlyAttachmentsPendingAndRoutingSystemMissing() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(eq("system.error.generic.feedback"))).thenReturn("Something went wrong");
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1").channelType(CHANNEL_TYPE).chatId("1")
                .messages(new ArrayList<>()).build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);

        AgentSystem attachmentsOnlySystem = new AgentSystem() {
            @Override
            public String getName() {
                return "attachmentsOnly";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                me.golemcore.bot.domain.model.Attachment attachment = me.golemcore.bot.domain.model.Attachment.builder()
                        .type(me.golemcore.bot.domain.model.Attachment.Type.IMAGE)
                        .data(new byte[] { 1, 2, 3 })
                        .filename("img.png")
                        .caption("img")
                        .build();
                context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder()
                        .attachment(attachment)
                        .build());
                return context;
            }
        };

        AgentLoop loop = new AgentLoop(
                sessionPort,
                rateLimitPort,
                List.of(attachmentsOnlySystem),
                new ChannelRegistry(List.of(channel)),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock,
                new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService()));

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("send image")
                .channelType(CHANNEL_TYPE)
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        verify(preferencesService, atLeastOnce()).getMessage("system.error.generic.feedback");
        verify(channel, never()).sendPhoto(any(), any(), any(), any());
        verify(channel, never()).sendMessage(eq("1"), eq("Something went wrong"), any());
    }

    @Test
    void shouldFallbackToGenericFeedbackWhenLlmInterpretationTimesOut() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(eq("system.error.generic.feedback"))).thenReturn("Something went wrong");
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(true);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")));

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1").channelType(CHANNEL_TYPE).chatId("1")
                .messages(new ArrayList<>()).build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // System that sets an LLM error but no response
        AgentSystem errorSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "errorSetter";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setAttribute(ContextAttributes.LLM_ERROR, "Connection refused");
                return context;
            }
        };

        ResponseRoutingSystem routing = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(channel)), preferencesService, mock(VoiceResponseHandler.class));

        AgentLoop loop = createLoop(
                sessionPort, rateLimitPort, List.of(errorSystem, routing), List.of(channel),
                mockRuntimeConfigService(1), preferencesService, llmPort, clock);

        Message inbound = Message.builder()
                .role(ROLE_USER).content("hi")
                .channelType(CHANNEL_TYPE).chatId("1").senderId("u1")
                .timestamp(clock.instant()).build();

        loop.processMessage(inbound);

        // LLM interpretation fails, falls through to generic feedback
        verify(channel, atLeastOnce()).sendMessage(eq("1"), eq("Something went wrong"), any());
    }

    @Test
    void shouldRecordReflectionCompletionOutcomeForAutoRunMessage() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s-auto")
                .channelType(CHANNEL_TYPE)
                .chatId("auto-chat")
                .messages(new ArrayList<>())
                .build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "auto-chat")).thenReturn(session);

        AgentSystem reflectionSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "reflectionSystem";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setAttribute(ContextAttributes.AUTO_REFLECTION_ACTIVE, true);
                context.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, "reviewer-skill");
                context.setTurnOutcome(TurnOutcome.builder()
                        .assistantText("Reflected answer")
                        .finishReason(FinishReason.SUCCESS)
                        .build());
                return context;
            }
        };

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(reflectionSystem),
                List.of(),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ContextAttributes.AUTO_MODE, true);
        metadata.put(ContextAttributes.AUTO_RUN_ID, "run-1");
        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("reflect")
                .channelType(CHANNEL_TYPE)
                .chatId("auto-chat")
                .senderId("auto")
                .metadata(metadata)
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        assertEquals("REFLECTION_COMPLETED", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_STATUS));
        assertEquals("SUCCESS", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_FINISH_REASON));
        assertEquals("Reflected answer", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT));
        assertEquals("reviewer-skill", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_ACTIVE_SKILL));
        assertNull(inbound.getMetadata().get(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY));
    }

    @Test
    void shouldRecordReflectionFailureOutcomeForAutoRunMessage() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s-auto-fail")
                .channelType(CHANNEL_TYPE)
                .chatId("auto-chat")
                .messages(new ArrayList<>())
                .build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "auto-chat")).thenReturn(session);

        AgentSystem reflectionFailureSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "reflectionFailureSystem";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return true;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setAttribute(ContextAttributes.AUTO_REFLECTION_ACTIVE, true);
                context.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, "planner-skill");
                context.addFailure(new FailureEvent(
                        FailureSource.LLM,
                        "test-llm",
                        FailureKind.EXCEPTION,
                        "Planner timeout",
                        clock.instant()));
                context.setTurnOutcome(TurnOutcome.builder()
                        .finishReason(FinishReason.ERROR)
                        .build());
                return context;
            }
        };

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(reflectionFailureSystem),
                List.of(),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ContextAttributes.AUTO_MODE, true);
        metadata.put(ContextAttributes.AUTO_RUN_ID, "run-2");
        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("reflect fail")
                .channelType(CHANNEL_TYPE)
                .chatId("auto-chat")
                .senderId("auto")
                .metadata(metadata)
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        assertEquals("REFLECTION_FAILED", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_STATUS));
        assertEquals("ERROR", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_FINISH_REASON));
        assertEquals("planner-skill", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_ACTIVE_SKILL));
        assertEquals("Planner timeout", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY));
        assertEquals("planner timeout", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT));
    }

    @Test
    void shouldRecordExplicitSkillAndTierEventsOnSystemSpans() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);
        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId("conv-1")
                .messages(new ArrayList<>())
                .build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "conv-1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        AgentSystem requestSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "SkillPipelineSystem";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setActiveSkill(me.golemcore.bot.domain.model.Skill.builder().name("analyzer").build());
                context.setSkillTransitionRequest(SkillTransitionRequest.pipeline("executor"));
                return context;
            }
        };

        AgentSystem applySystem = new AgentSystem() {
            @Override
            public String getName() {
                return "ContextBuildingSystem";
            }

            @Override
            public int getOrder() {
                return 2;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setActiveSkill(me.golemcore.bot.domain.model.Skill.builder()
                        .name("executor")
                        .modelTier("smart")
                        .build());
                context.setModelTier("smart");
                context.setAttribute("model.tier.source", "skill");
                context.clearSkillTransitionRequest();
                return context;
            }
        };

        AgentSystem dynamicTierSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "DynamicTierSystem";
            }

            @Override
            public int getOrder() {
                return 3;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setModelTier("coding");
                context.setAttribute("model.tier.source", "dynamic_tier");
                context.setOutgoingResponse(OutgoingResponse.textOnly("done"));
                return context;
            }
        };

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(requestSystem, applySystem, dynamicTierSystem),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("trace transitions")
                .channelType(CHANNEL_TYPE)
                .chatId("conv-1")
                .senderId("u1")
                .metadata(Map.of(
                        ContextAttributes.TRACE_ID, "trace-1",
                        ContextAttributes.TRACE_SPAN_ID, "span-1",
                        ContextAttributes.TRACE_ROOT_KIND, TraceSpanKind.INGRESS.name(),
                        ContextAttributes.TRACE_NAME, "telegram.message"))
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        TraceRecord trace = session.getTraces().get(0);
        TraceSpanRecord requestSpan = findSpan(trace, "system.SkillPipelineSystem");
        TraceEventRecord requested = findEvent(requestSpan, "skill.transition.requested");
        assertEquals("analyzer", requested.getAttributes().get("from_skill"));
        assertEquals("executor", requested.getAttributes().get("to_skill"));
        assertEquals("skill_pipeline", requested.getAttributes().get("source"));

        TraceSpanRecord applySpan = findSpan(trace, "system.ContextBuildingSystem");
        TraceEventRecord applied = findEvent(applySpan, "skill.transition.applied");
        assertEquals("analyzer", applied.getAttributes().get("from_skill"));
        assertEquals("executor", applied.getAttributes().get("to_skill"));
        assertEquals("skill_pipeline", applied.getAttributes().get("source"));

        TraceEventRecord tierResolved = findEvent(applySpan, "tier.resolved");
        assertEquals("executor", tierResolved.getAttributes().get("skill"));
        assertEquals("smart", tierResolved.getAttributes().get("tier"));
        assertEquals("gpt-5-smart", tierResolved.getAttributes().get("model_id"));
        assertEquals("skill", tierResolved.getAttributes().get("source"));

        TraceSpanRecord dynamicSpan = findSpan(trace, "system.DynamicTierSystem");
        TraceEventRecord tierTransition = findEvent(dynamicSpan, "tier.transition");
        assertEquals("smart", tierTransition.getAttributes().get("from_tier"));
        assertEquals("coding", tierTransition.getAttributes().get("to_tier"));
        assertEquals("gpt-5-smart", tierTransition.getAttributes().get("from_model_id"));
        assertEquals("gpt-5-coding", tierTransition.getAttributes().get("to_model_id"));
        assertEquals("dynamic_tier", tierTransition.getAttributes().get("source"));
    }

    @Test
    void shouldRecordSessionStateSkillRestoreOnContextBuildingSpan() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);
        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn(MSG_GENERIC);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);
        AgentSession session = AgentSession.builder()
                .id("s-restore")
                .channelType(CHANNEL_TYPE)
                .chatId("conv-restore")
                .messages(new ArrayList<>())
                .build();
        when(sessionPort.getOrCreate(CHANNEL_TYPE, "conv-restore")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        AgentSystem applySystem = new AgentSystem() {
            @Override
            public String getName() {
                return "ContextBuildingSystem";
            }

            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setActiveSkill(me.golemcore.bot.domain.model.Skill.builder()
                        .name("reviewer")
                        .modelTier("smart")
                        .build());
                context.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, "reviewer");
                context.setAttribute(ATTR_ACTIVE_SKILL_SOURCE, "session_state");
                context.setModelTier("smart");
                context.setAttribute(ContextAttributes.MODEL_TIER_SOURCE, "skill");
                context.setOutgoingResponse(OutgoingResponse.textOnly("done"));
                return context;
            }
        };

        AgentLoop loop = createLoop(
                sessionPort,
                rateLimitPort,
                List.of(applySystem),
                List.of(channel),
                mockRuntimeConfigService(1),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role(ROLE_USER)
                .content("resume skill")
                .channelType(CHANNEL_TYPE)
                .chatId("conv-restore")
                .senderId("u1")
                .metadata(Map.of(
                        ContextAttributes.TRACE_ID, "trace-restore",
                        ContextAttributes.TRACE_SPAN_ID, "span-restore",
                        ContextAttributes.TRACE_ROOT_KIND, TraceSpanKind.INGRESS.name(),
                        ContextAttributes.TRACE_NAME, "telegram.message"))
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        TraceRecord trace = session.getTraces().get(0);
        TraceSpanRecord applySpan = findSpan(trace, "system.ContextBuildingSystem");
        TraceEventRecord applied = findEvent(applySpan, "skill.transition.applied");
        assertEquals("reviewer", applied.getAttributes().get("to_skill"));
        assertEquals("session_state", applied.getAttributes().get("source"));

        TraceEventRecord tierResolved = findEvent(applySpan, "tier.resolved");
        assertEquals("reviewer", tierResolved.getAttributes().get("skill"));
        assertEquals("smart", tierResolved.getAttributes().get("tier"));
        assertEquals("skill", tierResolved.getAttributes().get("source"));
    }

    private static AgentLoop createLoop(
            SessionPort sessionPort,
            RateLimitPort rateLimitPort,
            List<AgentSystem> systems,
            List<ChannelPort> channels,
            RuntimeConfigService runtimeConfigService,
            UserPreferencesService preferencesService,
            LlmPort llmPort,
            Clock clock) {
        return new AgentLoop(
                sessionPort,
                rateLimitPort,
                systems,
                new ChannelRegistry(channels),
                runtimeConfigService,
                preferencesService,
                llmPort,
                clock,
                new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService()));
    }

    private static RuntimeConfigService mockRuntimeConfigService(int maxLlmCalls) {
        RuntimeConfigService rcs = mock(RuntimeConfigService.class);
        when(rcs.getTurnMaxLlmCalls()).thenReturn(maxLlmCalls);
        when(rcs.getRoutingModel()).thenReturn("test-model");
        when(rcs.getRoutingModelReasoning()).thenReturn("none");
        when(rcs.getBalancedModel()).thenReturn("gpt-5-balanced");
        when(rcs.getBalancedModelReasoning()).thenReturn("medium");
        when(rcs.getSmartModel()).thenReturn("gpt-5-smart");
        when(rcs.getSmartModelReasoning()).thenReturn("high");
        when(rcs.getCodingModel()).thenReturn("gpt-5-coding");
        when(rcs.getCodingModelReasoning()).thenReturn("high");
        when(rcs.getDeepModel()).thenReturn("gpt-5-deep");
        when(rcs.getDeepModelReasoning()).thenReturn("high");
        when(rcs.isTracingEnabled()).thenReturn(true);
        return rcs;
    }

    private static TraceSpanRecord findSpan(TraceRecord trace, String name) {
        return trace.getSpans().stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static TraceEventRecord findEvent(TraceSpanRecord span, String name) {
        return span.getEvents().stream()
                .filter(event -> name.equals(event.getName()))
                .findFirst()
                .orElseThrow();
    }
}
