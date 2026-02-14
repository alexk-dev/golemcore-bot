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
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RateLimitResult;
import me.golemcore.bot.domain.model.SkillTransitionRequest;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.RateLimitPort;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentLoopTest {

    @Test
    void shouldResetTypedControlFlagsAndToolResultsBetweenIterations() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);
        BotProperties props = new BotProperties();
        props.getAgent().setMaxIterations(2);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any(), any())).thenReturn("x");
        when(preferencesService.getMessage(any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate("telegram", "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn("telegram");
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

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
                    context.setFinalAnswerReady(true);
                    context.setSkillTransitionRequest(SkillTransitionRequest.pipeline("next"));
                    context.addToolResult("tc1", ToolResult.success("ok"));
                    context.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
                            OutgoingResponse.text("hello"));
                    return context;
                }

                assertFalse(context.isFinalAnswerReady(), "finalAnswerReady must be reset between iterations");
                assertNull(context.getSkillTransitionRequest(),
                        "skillTransitionRequest must be cleared between iterations");
                assertTrue(context.getToolResults().isEmpty(), "toolResults must be cleared between iterations");
                return context;
            }
        };

        AgentLoop loop = new AgentLoop(
                sessionPort,
                rateLimitPort,
                props,
                List.of(verifier),
                List.of(channel),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role("user")
                .content("hi")
                .channelType("telegram")
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        verify(sessionPort, times(1)).save(session);
    }

    @Test
    void shouldSendUnsentLlmResponseAsFeedbackGuarantee() {
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);
        BotProperties props = new BotProperties();
        props.getAgent().setMaxIterations(1);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(any())).thenReturn("generic");
        when(preferencesService.getMessage(any(), any())).thenReturn("x");

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        Clock clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC);

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("1")
                .messages(new ArrayList<>())
                .build();

        when(sessionPort.getOrCreate("telegram", "1")).thenReturn(session);
        when(rateLimitPort.tryConsume()).thenReturn(RateLimitResult.allowed(0));

        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn("telegram");
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

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
                context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.text("hello"));
                return context;
            }
        };

        AgentLoop loop = new AgentLoop(
                sessionPort,
                rateLimitPort,
                props,
                List.of(system,
                        new me.golemcore.bot.domain.system.ResponseRoutingSystem(List.of(channel), preferencesService,
                                mock(me.golemcore.bot.domain.service.VoiceResponseHandler.class))),
                List.of(channel),
                preferencesService,
                llmPort,
                clock);

        Message inbound = Message.builder()
                .role("user")
                .content("hi")
                .channelType("telegram")
                .chatId("1")
                .senderId("u1")
                .timestamp(clock.instant())
                .build();

        loop.processMessage(inbound);

        // Feedback guarantee should route via ResponseRoutingSystem when present.
        verify(channel, atLeastOnce()).sendMessage(eq("1"), eq("hello"));
        // NOTE: ResponseRoutingSystem is transport-only. It must not write assistant
        // messages
        // into raw history. (Raw history is owned by the domain execution path.)
    }
}
