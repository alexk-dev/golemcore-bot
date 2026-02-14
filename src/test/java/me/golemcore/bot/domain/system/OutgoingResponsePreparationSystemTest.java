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
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutgoingResponsePreparationSystemTest {

    private static final String REPLY_TEXT = "Reply";

    private UserPreferencesService preferencesService;
    private BotProperties properties;
    private OutgoingResponsePreparationSystem system;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        properties = new BotProperties();
        system = new OutgoingResponsePreparationSystem(preferencesService, properties);
    }

    @Test
    void shouldReturnCorrectNameAndOrder() {
        assertEquals("OutgoingResponsePreparationSystem", system.getName());
        assertEquals(59, system.getOrder());
    }

    @Test
    void shouldSkipWhenOutgoingResponseAlreadyPresent() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("already set"));

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldConvertLlmErrorToOutgoingResponse() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_ERROR, "timeout");
        when(preferencesService.getMessage("system.error.llm")).thenReturn("Something went wrong");

        assertTrue(system.shouldProcess(context));
        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals("Something went wrong", outgoing.getText());
        assertFalse(outgoing.isVoiceRequested());
    }

    @Test
    void shouldConvertLlmResponseToTextResponse() {
        AgentContext context = buildContext();
        LlmResponse llmResponse = LlmResponse.builder().content("Hello there").build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, llmResponse);

        assertTrue(system.shouldProcess(context));
        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals("Hello there", outgoing.getText());
        assertFalse(outgoing.isVoiceRequested());
    }

    @Test
    void shouldDetectVoicePrefixAndBuildVoiceResponse() {
        AgentContext context = buildContext();
        LlmResponse llmResponse = LlmResponse.builder()
                .content("\uD83D\uDD0A Here is a spoken response")
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, llmResponse);

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals("Here is a spoken response", outgoing.getText());
        assertTrue(outgoing.isVoiceRequested());
        assertEquals("Here is a spoken response", outgoing.getVoiceText());
    }

    @Test
    void shouldHandleVoicePrefixWithBlankContent() {
        AgentContext context = buildContext();
        LlmResponse llmResponse = LlmResponse.builder()
                .content("\uD83D\uDD0A   ")
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, llmResponse);

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNull(outgoing);
    }

    @Test
    void shouldAutoVoiceRespondWhenIncomingVoice() {
        properties.getVoice().getTelegram().setRespondWithVoice(true);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder()
                .role("user")
                .content("voice message")
                .voiceData(new byte[] { 1, 2, 3 })
                .timestamp(Instant.now())
                .build());

        AgentContext context = AgentContext.builder()
                .session(buildSession())
                .messages(messages)
                .maxIterations(1)
                .currentIteration(0)
                .build();

        LlmResponse llmResponse = LlmResponse.builder().content(REPLY_TEXT).build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, llmResponse);

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals(REPLY_TEXT, outgoing.getText());
        assertTrue(outgoing.isVoiceRequested());
    }

    @Test
    void shouldNotAutoVoiceWhenConfigDisabled() {
        properties.getVoice().getTelegram().setRespondWithVoice(false);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder()
                .role("user")
                .content("voice message")
                .voiceData(new byte[] { 1, 2, 3 })
                .timestamp(Instant.now())
                .build());

        AgentContext context = AgentContext.builder()
                .session(buildSession())
                .messages(messages)
                .maxIterations(1)
                .currentIteration(0)
                .build();

        LlmResponse llmResponse = LlmResponse.builder().content(REPLY_TEXT).build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, llmResponse);

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals(REPLY_TEXT, outgoing.getText());
        assertFalse(outgoing.isVoiceRequested());
    }

    @Test
    void shouldDoNothingWhenNoLlmResponseOrError() {
        AgentContext context = buildContext();

        assertFalse(system.shouldProcess(context));

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNull(outgoing);
    }

    @Test
    void shouldPreferToolVoiceTextOverStrippedPrefix() {
        AgentContext context = buildContext();
        LlmResponse llmResponse = LlmResponse.builder()
                .content("\uD83D\uDD0A Full spoken text here")
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, llmResponse);
        context.setAttribute(ContextAttributes.VOICE_TEXT, "Custom voice text from tool");

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals("Full spoken text here", outgoing.getText());
        assertTrue(outgoing.isVoiceRequested());
        assertEquals("Custom voice text from tool", outgoing.getVoiceText());
    }

    private AgentContext buildContext() {
        return AgentContext.builder()
                .session(buildSession())
                .messages(new ArrayList<>())
                .maxIterations(1)
                .currentIteration(0)
                .build();
    }

    private AgentSession buildSession() {
        return AgentSession.builder()
                .id("test-session")
                .channelType("telegram")
                .chatId("123")
                .messages(new ArrayList<>())
                .build();
    }
}
