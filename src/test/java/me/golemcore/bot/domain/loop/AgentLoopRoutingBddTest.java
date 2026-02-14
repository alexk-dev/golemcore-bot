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

class AgentLoopRoutingBddTest {

    @Test
    void scenario_iterationLimit_shouldRouteSyntheticLimitMessageViaRoutingSystem() {
        // Given
        SessionPort sessionPort = mock(SessionPort.class);
        RateLimitPort rateLimitPort = mock(RateLimitPort.class);

        BotProperties props = new BotProperties();
        props.getAgent().setMaxIterations(2);

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        // NOTE: getMessage(key, args...) is varargs, so exact matching on (key, 2)
        // is brittle. Match varargs properly.
        when(preferencesService.getMessage(any(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if ("system.iteration.limit".equals(key)) {
                return "LIMIT";
            }
            if ("system.error.generic.feedback".equals(key)) {
                return "generic";
            }
            return "x";
        });

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

        // System A: requests next iteration, which is impossible due to maxIterations=1
        // -> triggers iteration limit path.
        AgentSystem requester = new AgentSystem() {
            @Override
            public String getName() {
                return "requester";
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
                    context.setSkillTransitionRequest(SkillTransitionRequest.pipeline("next"));
                } else {
                    context.setSkillTransitionRequest(SkillTransitionRequest.pipeline("next"));
                }
                return context;
            }
        };

        // Routing system stub that actually performs transport via ChannelPort.
        AgentSystem routing = new AgentSystem() {
            @Override
            public String getName() {
                return "ResponseRoutingSystem";
            }

            @Override
            public int getOrder() {
                return 60;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return context.getAttribute(ContextAttributes.OUTGOING_RESPONSE) != null;
            }

            @Override
            public AgentContext process(AgentContext context) {
                OutgoingResponse response = context.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
                assertNotNull(response);
                channel.sendMessage(session.getChatId(), response.getText());
                context.setAttribute(ContextAttributes.RESPONSE_SENT, true);
                return context;
            }
        };

        AgentLoop loop = new AgentLoop(
                sessionPort,
                rateLimitPort,
                props,
                List.of(requester, routing),
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

        // When
        loop.processMessage(inbound);

        // Then
        verify(channel, atLeastOnce()).sendMessage("1", "LIMIT");
        assertTrue(session.getMessages().stream().anyMatch(m -> "assistant".equals(m.getRole())
                && "LIMIT".equals(m.getContent())));
    }
}
