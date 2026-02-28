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
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.SkillTransitionRequest;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackGuaranteeSystemTest {

    private static final String FALLBACK_TEXT = "Something went wrong. Please try again.";
    private static final String FALLBACK_KEY = "system.error.generic.feedback";
    private static final String ROLE_USER = "user";
    private static final String AUTO_MODE_KEY = "auto.mode";
    private static final String HELLO = "hello";

    private UserPreferencesService preferencesService;
    private FeedbackGuaranteeSystem system;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(FALLBACK_KEY)).thenReturn(FALLBACK_TEXT);
        system = new FeedbackGuaranteeSystem(preferencesService);
    }

    // ── identity ──

    @Test
    void shouldReturnCorrectNameAndOrder() {
        assertEquals("FeedbackGuaranteeSystem", system.getName());
        assertEquals(59, system.getOrder());
    }

    // ── shouldProcess ──

    @Test
    void shouldNotProcessWhenOutgoingResponseAlreadyPresent() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("ok"));

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenTransitionPending() {
        AgentContext context = buildContext();
        context.setSkillTransitionRequest(SkillTransitionRequest.pipeline("next"));

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessInAutoMode() {
        Message autoMessage = Message.builder()
                .role(ROLE_USER)
                .content("auto")
                .timestamp(Instant.now())
                .metadata(Map.of(AUTO_MODE_KEY, true))
                .build();

        AgentContext context = buildContextWithMessages(List.of(autoMessage));

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenOutgoingResponsePresentAndAutoMode() {
        Message autoMessage = Message.builder()
                .role(ROLE_USER)
                .content("auto")
                .timestamp(Instant.now())
                .metadata(Map.of(AUTO_MODE_KEY, true))
                .build();

        AgentContext context = buildContextWithMessages(List.of(autoMessage));
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("ok"));

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenNoOutgoingResponseInNormalMode() {
        AgentContext context = buildContextWithMessages(List.of(
                Message.builder()
                        .role(ROLE_USER)
                        .content(HELLO)
                        .timestamp(Instant.now())
                        .build()));

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenMessagesListIsEmpty() {
        AgentContext context = buildContext();

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenMessagesListIsNull() {
        AgentContext context = AgentContext.builder()
                .session(buildSession())
                .messages(null)
                .build();

        assertTrue(system.shouldProcess(context));
    }

    // ── process: fallback production ──

    @Test
    void shouldProduceFallbackWhenNoOutgoingResponse() {
        AgentContext context = buildContextWithMessages(List.of(
                Message.builder()
                        .role(ROLE_USER)
                        .content(HELLO)
                        .timestamp(Instant.now())
                        .build()));

        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals(FALLBACK_TEXT, outgoing.getText());
        assertFalse(outgoing.isVoiceRequested());
    }

    @Test
    void shouldUseFallbackMessageFromPreferencesService() {
        String customFallback = "Custom fallback message";
        when(preferencesService.getMessage(FALLBACK_KEY)).thenReturn(customFallback);

        AgentContext context = buildContext();
        AgentContext result = system.process(context);

        OutgoingResponse outgoing = result.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals(customFallback, outgoing.getText());
    }

    // ── process: defensive guards ──

    @Test
    void shouldNotOverrideExistingOutgoingResponse() {
        AgentContext context = buildContext();
        OutgoingResponse existing = OutgoingResponse.textOnly("pre-existing");
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, existing);

        AgentContext result = system.process(context);

        assertSame(existing, result.getAttribute(ContextAttributes.OUTGOING_RESPONSE));
        verify(preferencesService, never()).getMessage(FALLBACK_KEY);
    }

    @Test
    void shouldNotProduceFallbackInAutoMode() {
        Message autoMessage = Message.builder()
                .role(ROLE_USER)
                .content("auto")
                .timestamp(Instant.now())
                .metadata(Map.of(AUTO_MODE_KEY, true))
                .build();

        AgentContext context = buildContextWithMessages(List.of(autoMessage));

        AgentContext result = system.process(context);

        assertNull(result.getAttribute(ContextAttributes.OUTGOING_RESPONSE));
        verify(preferencesService, never()).getMessage(FALLBACK_KEY);
    }

    @Test
    void shouldNotProduceFallbackWhenTransitionPending() {
        AgentContext context = buildContext();
        context.setSkillTransitionRequest(SkillTransitionRequest.pipeline("next"));

        AgentContext result = system.process(context);

        assertNull(result.getAttribute(ContextAttributes.OUTGOING_RESPONSE));
        verify(preferencesService, never()).getMessage(FALLBACK_KEY);
    }

    // ── raw-history purity ──

    @Test
    void shouldNotMutateSessionMessages() {
        AgentSession session = buildSession();
        int messagesBefore = session.getMessages().size();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();

        system.process(context);

        assertEquals(messagesBefore, session.getMessages().size(),
                "FeedbackGuaranteeSystem must not append messages to raw session history");
    }

    // ── auto-mode edge cases ──

    @Test
    void shouldNotTreatNonAutoMetadataAsAutoMode() {
        Message messageWithMetadata = Message.builder()
                .role(ROLE_USER)
                .content(HELLO)
                .timestamp(Instant.now())
                .metadata(Map.of("some.flag", true))
                .build();

        AgentContext context = buildContextWithMessages(List.of(messageWithMetadata));

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldCheckOnlyLastMessageForAutoMode() {
        Message autoMessage = Message.builder()
                .role(ROLE_USER)
                .content("auto trigger")
                .timestamp(Instant.now())
                .metadata(Map.of(AUTO_MODE_KEY, true))
                .build();
        Message normalMessage = Message.builder()
                .role(ROLE_USER)
                .content("normal")
                .timestamp(Instant.now())
                .build();

        AgentContext context = buildContextWithMessages(List.of(autoMessage, normalMessage));

        assertTrue(system.shouldProcess(context),
                "Should check only the last message for auto mode");
    }

    @Test
    void shouldDetectAutoModeOnLastMessage() {
        Message normalMessage = Message.builder()
                .role(ROLE_USER)
                .content("normal")
                .timestamp(Instant.now())
                .build();
        Message autoMessage = Message.builder()
                .role(ROLE_USER)
                .content("auto trigger")
                .timestamp(Instant.now())
                .metadata(Map.of(AUTO_MODE_KEY, true))
                .build();

        AgentContext context = buildContextWithMessages(List.of(normalMessage, autoMessage));

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldTreatAutoModeFalseAsNormalMode() {
        Message message = Message.builder()
                .role(ROLE_USER)
                .content(HELLO)
                .timestamp(Instant.now())
                .metadata(Map.of(AUTO_MODE_KEY, false))
                .build();

        AgentContext context = buildContextWithMessages(List.of(message));

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldTreatNullMetadataAsNormalMode() {
        Message message = Message.builder()
                .role(ROLE_USER)
                .content(HELLO)
                .timestamp(Instant.now())
                .metadata(null)
                .build();

        AgentContext context = buildContextWithMessages(List.of(message));

        assertTrue(system.shouldProcess(context));
    }

    // ── helpers ──

    private AgentContext buildContext() {
        return AgentContext.builder()
                .session(buildSession())
                .messages(new ArrayList<>())
                .build();
    }

    private AgentContext buildContextWithMessages(List<Message> messages) {
        return AgentContext.builder()
                .session(buildSession())
                .messages(new ArrayList<>(messages))
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
