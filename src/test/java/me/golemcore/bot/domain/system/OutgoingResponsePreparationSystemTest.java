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
    private static final String VOICE_PREFIX = "\uD83D\uDD0A";
    private static final String ERROR_MESSAGE = "Something went wrong";
    private static final String LLM_ERROR_VALUE = "timeout";
    private static final String ROLE_USER = "user";

    private UserPreferencesService preferencesService;
    private BotProperties properties;
    private OutgoingResponsePreparationSystem system;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        properties = new BotProperties();
        system = new OutgoingResponsePreparationSystem(preferencesService, properties);
    }

    // ── identity ──

    @Test
    void shouldReturnCorrectNameAndOrder() {
        assertEquals("OutgoingResponsePreparationSystem", system.getName());
        assertEquals(58, system.getOrder());
    }

    // ── shouldProcess ──

    @Test
    void shouldNotProcessWhenOutgoingResponseAlreadyPresent() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("already set"));

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenLlmErrorPresent() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_ERROR, LLM_ERROR_VALUE);

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenLlmResponsePresent() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("hello").build());

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenNoLlmResponseOrError() {
        AgentContext context = buildContext();

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenOutgoingResponseAlreadyPresentDespiteLlmError() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("pre-set"));
        context.setAttribute(ContextAttributes.LLM_ERROR, LLM_ERROR_VALUE);

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenOutgoingResponseAlreadyPresentDespiteLlmResponse() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("pre-set"));
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("hello").build());

        assertFalse(system.shouldProcess(context));
    }

    // ── process: defensive guard ──

    @Test
    void shouldReturnContextUnchangedWhenProcessCalledWithExistingOutgoingResponse() {
        AgentContext context = buildContext();
        OutgoingResponse existing = OutgoingResponse.textOnly("pre-existing");
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, existing);

        AgentContext result = system.process(context);

        assertSame(existing, result.getAttribute(ContextAttributes.OUTGOING_RESPONSE));
    }

    // ── process: LLM error path ──

    @Test
    void shouldConvertLlmErrorToOutgoingResponse() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_ERROR, LLM_ERROR_VALUE);
        when(preferencesService.getMessage("system.error.llm")).thenReturn(ERROR_MESSAGE);

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals(ERROR_MESSAGE, outgoing.getText());
        assertFalse(outgoing.isVoiceRequested());
    }

    @Test
    void shouldPrioritizeLlmErrorOverLlmResponse() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_ERROR, LLM_ERROR_VALUE);
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("some content").build());
        when(preferencesService.getMessage("system.error.llm")).thenReturn(ERROR_MESSAGE);

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals(ERROR_MESSAGE, outgoing.getText());
    }

    // ── process: LLM response → text ──

    @Test
    void shouldConvertLlmResponseToTextResponse() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("Hello there").build());

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals("Hello there", outgoing.getText());
        assertFalse(outgoing.isVoiceRequested());
    }

    @Test
    void shouldNotCreateOutgoingResponseWhenLlmResponseContentIsNull() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(null).build());

        AgentContext result = system.process(context);

        assertNull(result.getAttribute(ContextAttributes.OUTGOING_RESPONSE));
    }

    @Test
    void shouldNotCreateOutgoingResponseWhenLlmResponseContentIsBlank() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("   ").build());

        AgentContext result = system.process(context);

        assertNull(result.getAttribute(ContextAttributes.OUTGOING_RESPONSE));
    }

    @Test
    void shouldNotCreateOutgoingResponseWhenLlmResponseContentIsEmpty() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("").build());

        AgentContext result = system.process(context);

        assertNull(result.getAttribute(ContextAttributes.OUTGOING_RESPONSE));
    }

    // ── process: voice prefix detection ──

    @Test
    void shouldDetectVoicePrefixAndBuildVoiceResponse() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(VOICE_PREFIX + " Here is a spoken response").build());

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
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(VOICE_PREFIX + "   ").build());

        AgentContext result = system.process(context);

        assertNull(result.getAttribute(ContextAttributes.OUTGOING_RESPONSE));
    }

    @Test
    void shouldHandleVoicePrefixAlone() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(VOICE_PREFIX).build());

        AgentContext result = system.process(context);

        assertNull(result.getAttribute(ContextAttributes.OUTGOING_RESPONSE));
    }

    @Test
    void shouldHandleVoicePrefixWithLeadingWhitespace() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("  " + VOICE_PREFIX + " trimmed text").build());

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals("trimmed text", outgoing.getText());
        assertTrue(outgoing.isVoiceRequested());
    }

    @Test
    void shouldPreferToolVoiceTextOverStrippedPrefix() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(VOICE_PREFIX + " Full spoken text here").build());
        context.setVoiceText("Custom voice text from tool");

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals("Full spoken text here", outgoing.getText());
        assertTrue(outgoing.isVoiceRequested());
        assertEquals("Custom voice text from tool", outgoing.getVoiceText());
    }

    @Test
    void shouldUseStrippedPrefixAsVoiceTextWhenToolVoiceTextIsBlank() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(VOICE_PREFIX + " Spoken text").build());
        context.setVoiceText("   ");

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals("Spoken text", outgoing.getVoiceText());
    }

    // ── process: explicit voiceRequested / voiceText (no prefix) ──

    @Test
    void shouldBuildVoiceResponseWhenVoiceRequestedAttributeSet() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(REPLY_TEXT).build());
        context.setVoiceRequested(true);

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals(REPLY_TEXT, outgoing.getText());
        assertTrue(outgoing.isVoiceRequested());
    }

    @Test
    void shouldNotTreatFalseVoiceRequestedAsVoice() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(REPLY_TEXT).build());
        context.setVoiceRequested(false);

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertFalse(outgoing.isVoiceRequested());
    }

    @Test
    void shouldBuildVoiceResponseWhenOnlyVoiceTextSet() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(REPLY_TEXT).build());
        context.setVoiceText("speak this");

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals(REPLY_TEXT, outgoing.getText());
        assertTrue(outgoing.isVoiceRequested());
        assertEquals("speak this", outgoing.getVoiceText());
    }

    @Test
    void shouldCreateVoiceOnlyResponseWhenNoTextButVoiceRequested() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(null).build());
        context.setVoiceRequested(true);

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertNull(outgoing.getText());
        assertTrue(outgoing.isVoiceRequested());
    }

    @Test
    void shouldCreateVoiceOnlyResponseWhenNoTextButVoiceTextSet() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("").build());
        context.setVoiceText("voice content");

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertNull(outgoing.getText());
        assertTrue(outgoing.isVoiceRequested());
        assertEquals("voice content", outgoing.getVoiceText());
    }

    // ── process: auto-voice ──

    @Test
    void shouldAutoVoiceRespondWhenIncomingVoice() {
        properties.getVoice().getTelegram().setRespondWithVoice(true);

        AgentContext context = buildContextWithMessages(List.of(
                Message.builder()
                        .role(ROLE_USER)
                        .content("voice message")
                        .voiceData(new byte[] { 1, 2, 3 })
                        .timestamp(Instant.now())
                        .build()));

        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(REPLY_TEXT).build());

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals(REPLY_TEXT, outgoing.getText());
        assertTrue(outgoing.isVoiceRequested());
    }

    @Test
    void shouldNotAutoVoiceWhenConfigDisabled() {
        properties.getVoice().getTelegram().setRespondWithVoice(false);

        AgentContext context = buildContextWithMessages(List.of(
                Message.builder()
                        .role(ROLE_USER)
                        .content("voice message")
                        .voiceData(new byte[] { 1, 2, 3 })
                        .timestamp(Instant.now())
                        .build()));

        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(REPLY_TEXT).build());

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals(REPLY_TEXT, outgoing.getText());
        assertFalse(outgoing.isVoiceRequested());
    }

    @Test
    void shouldNotAutoVoiceWhenLastUserMessageHasNoVoiceData() {
        properties.getVoice().getTelegram().setRespondWithVoice(true);

        AgentContext context = buildContextWithMessages(List.of(
                Message.builder()
                        .role(ROLE_USER)
                        .content("text only message")
                        .timestamp(Instant.now())
                        .build()));

        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(REPLY_TEXT).build());

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertFalse(outgoing.isVoiceRequested());
    }

    @Test
    void shouldNotAutoVoiceWhenOnlyAssistantMessagesPresent() {
        properties.getVoice().getTelegram().setRespondWithVoice(true);

        AgentContext context = buildContextWithMessages(List.of(
                Message.builder()
                        .role("assistant")
                        .content("previous response")
                        .timestamp(Instant.now())
                        .build()));

        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(REPLY_TEXT).build());

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertFalse(outgoing.isVoiceRequested());
    }

    @Test
    void shouldNotAutoVoiceWhenMessagesListIsEmpty() {
        properties.getVoice().getTelegram().setRespondWithVoice(true);

        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(REPLY_TEXT).build());

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertFalse(outgoing.isVoiceRequested());
    }

    @Test
    void shouldNotAutoVoiceWhenMessagesListIsNull() {
        properties.getVoice().getTelegram().setRespondWithVoice(true);

        AgentContext context = AgentContext.builder()
                .session(buildSession())
                .messages(null)
                .maxIterations(1)
                .currentIteration(0)
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(REPLY_TEXT).build());

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertFalse(outgoing.isVoiceRequested());
    }

    @Test
    void shouldSkipAutoVoiceWhenVoiceAlreadyDetected() {
        properties.getVoice().getTelegram().setRespondWithVoice(true);

        AgentContext context = buildContextWithMessages(List.of(
                Message.builder()
                        .role(ROLE_USER)
                        .content("voice message")
                        .voiceData(new byte[] { 1, 2, 3 })
                        .timestamp(Instant.now())
                        .build()));

        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(REPLY_TEXT).build());
        context.setVoiceRequested(true);

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertTrue(outgoing.isVoiceRequested());
    }

    @Test
    void shouldFindLastUserMessageAmongMixedMessages() {
        properties.getVoice().getTelegram().setRespondWithVoice(true);

        AgentContext context = buildContextWithMessages(List.of(
                Message.builder()
                        .role(ROLE_USER)
                        .content("first user msg")
                        .voiceData(new byte[] { 1, 2, 3 })
                        .timestamp(Instant.now())
                        .build(),
                Message.builder()
                        .role("assistant")
                        .content("bot reply")
                        .timestamp(Instant.now())
                        .build(),
                Message.builder()
                        .role(ROLE_USER)
                        .content("second user msg without voice")
                        .timestamp(Instant.now())
                        .build()));

        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(REPLY_TEXT).build());

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertFalse(outgoing.isVoiceRequested(),
                "Should check last user message, not earlier voice message");
    }

    // ── process: edge case — no attributes at all ──

    @Test
    void shouldDoNothingWhenNoAttributesSet() {
        AgentContext context = buildContext();

        AgentContext result = system.process(context);

        assertNull(result.getAttribute(ContextAttributes.OUTGOING_RESPONSE));
    }

    // ── helpers ──

    private AgentContext buildContext() {
        return AgentContext.builder()
                .session(buildSession())
                .messages(new ArrayList<>())
                .maxIterations(1)
                .currentIteration(0)
                .build();
    }

    private AgentContext buildContextWithMessages(List<Message> messages) {
        return AgentContext.builder()
                .session(buildSession())
                .messages(new ArrayList<>(messages))
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
