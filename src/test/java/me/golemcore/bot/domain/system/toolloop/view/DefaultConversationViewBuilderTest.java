package me.golemcore.bot.domain.system.toolloop.view;

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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultConversationViewBuilderTest {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String TOOL_CALL_ID = "tc1";
    private static final String TOOL_NAME = "shell";

    @Test
    void shouldReturnRawMessagesWhenSessionMissing() {
        DefaultConversationViewBuilder builder = new DefaultConversationViewBuilder(new FlatteningToolMessageMasker());

        List<Message> raw = List.of(Message.builder().role("user").content("hi").build());
        AgentContext ctx = AgentContext.builder().messages(new ArrayList<>(raw)).build();

        ConversationView view = builder.buildView(ctx, "model-a");

        assertEquals(1, view.messages().size());
        assertEquals("user", view.messages().get(0).getRole());
        assertTrue(view.diagnostics().isEmpty());
    }

    @Test
    void shouldNotMaskWhenModelDidNotChange() {
        DefaultConversationViewBuilder builder = new DefaultConversationViewBuilder(new FlatteningToolMessageMasker());

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("1")
                .metadata(new HashMap<>(Map.of(ContextAttributes.LLM_MODEL, "same")))
                .messages(new ArrayList<>())
                .build();

        Message assistantWithToolCall = Message.builder()
                .role(ROLE_ASSISTANT)
                .content("Calling tool")
                .toolCalls(List.of(Message.ToolCall.builder().id(TOOL_CALL_ID).name(TOOL_NAME)
                        .arguments(Map.of("command", "echo hi"))
                        .build()))
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        session.addMessage(assistantWithToolCall);

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        ConversationView view = builder.buildView(ctx, "same");

        assertEquals(1, view.messages().size());
        assertTrue(view.diagnostics().isEmpty());
        assertTrue(view.messages().get(0).hasToolCalls(), "no masking expected if model is unchanged");
    }

    @Test
    void shouldMaskWhenModelChangedAndToolCallsPresent() {
        DefaultConversationViewBuilder builder = new DefaultConversationViewBuilder(new FlatteningToolMessageMasker());

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("1")
                .metadata(new HashMap<>(Map.of(ContextAttributes.LLM_MODEL, "old")))
                .messages(new ArrayList<>())
                .build();

        Message assistantWithToolCall = Message.builder()
                .role(ROLE_ASSISTANT)
                .content("Calling tool")
                .toolCalls(List.of(Message.ToolCall.builder().id(TOOL_CALL_ID).name(TOOL_NAME)
                        .arguments(Map.of("command", "echo hi"))
                        .build()))
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        Message toolResult = Message.builder()
                .role("tool")
                .toolCallId(TOOL_CALL_ID)
                .toolName(TOOL_NAME)
                .content("hi")
                .timestamp(Instant.parse("2026-01-01T00:00:01Z"))
                .build();

        session.addMessage(assistantWithToolCall);
        session.addMessage(toolResult);

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        ConversationView view = builder.buildView(ctx, "new");

        assertEquals(2, view.messages().size());
        assertFalse(view.diagnostics().isEmpty());
        assertEquals(ROLE_ASSISTANT, view.messages().get(0).getRole());
        assertFalse(view.messages().get(0).hasToolCalls(), "toolCalls must be masked in view");
        assertTrue(view.messages().get(0).getContent().contains("masked"));

        // The tool message becomes assistant text too (flattening behavior).
        assertEquals(ROLE_ASSISTANT, view.messages().get(1).getRole());
    }

    @Test
    void shouldMaskWhenPreviousModelMissingButToolMessagesPresentAndTargetModelProvided() {
        DefaultConversationViewBuilder builder = new DefaultConversationViewBuilder(new FlatteningToolMessageMasker());

        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("1")
                .metadata(new HashMap<>())
                .messages(new ArrayList<>())
                .build();

        Message toolResult = Message.builder()
                .role("tool")
                .toolCallId(TOOL_CALL_ID)
                .toolName(TOOL_NAME)
                .content("hi")
                .timestamp(Instant.parse("2026-01-01T00:00:01Z"))
                .build();

        session.addMessage(toolResult);

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        ConversationView view = builder.buildView(ctx, "new");

        assertEquals(1, view.messages().size());
        assertFalse(view.diagnostics().isEmpty());
        assertEquals(ROLE_ASSISTANT, view.messages().get(0).getRole());
    }
}
