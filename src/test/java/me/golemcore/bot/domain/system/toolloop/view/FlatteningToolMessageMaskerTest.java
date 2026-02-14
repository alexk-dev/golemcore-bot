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

import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlatteningToolMessageMaskerTest {

    @Test
    void shouldReturnEmptyResultForNullOrEmptyInput() {
        FlatteningToolMessageMasker masker = new FlatteningToolMessageMasker();

        ToolMessageMasker.MaskingResult nullResult = masker.maskToolMessages(null);
        assertTrue(nullResult.messages().isEmpty());
        assertTrue(nullResult.diagnostics().isEmpty());

        ToolMessageMasker.MaskingResult emptyResult = masker.maskToolMessages(List.of());
        assertTrue(emptyResult.messages().isEmpty());
        assertTrue(emptyResult.diagnostics().isEmpty());
    }

    @Test
    void shouldSkipNullMessages() {
        FlatteningToolMessageMasker masker = new FlatteningToolMessageMasker();

        Message user = Message.builder().role("user").content("hi").build();
        ToolMessageMasker.MaskingResult result = masker.maskToolMessages(java.util.Arrays.asList(null, user, null));

        assertEquals(1, result.messages().size());
        assertEquals("user", result.messages().get(0).getRole());
    }

    @Test
    void shouldAddNoOpDiagnosticWhenNothingFlattened() {
        FlatteningToolMessageMasker masker = new FlatteningToolMessageMasker();

        Message user = Message.builder().role("user").content("hi").build();
        ToolMessageMasker.MaskingResult result = masker.maskToolMessages(List.of(user));

        assertEquals(1, result.messages().size());
        assertEquals("user", result.messages().get(0).getRole());
        assertEquals(List.of("no-op: no tool messages found"), result.diagnostics());
    }

    @Test
    void shouldFlattenToolMessageWithNullFields() {
        FlatteningToolMessageMasker masker = new FlatteningToolMessageMasker();

        Message tool = Message.builder()
                .id("m1")
                .role("tool")
                .toolCallId("tc1")
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        ToolMessageMasker.MaskingResult result = masker.maskToolMessages(List.of(tool));

        assertEquals(1, result.messages().size());
        Message flattened = result.messages().get(0);
        assertEquals("assistant", flattened.getRole());
        assertTrue(flattened.getContent().contains("[Tool result: tool]"));
        assertFalse(result.diagnostics().isEmpty());
    }

    @Test
    void shouldFlattenAssistantToolCallMessageWithNullContent() {
        FlatteningToolMessageMasker masker = new FlatteningToolMessageMasker();

        Message assistantWithCall = Message.builder()
                .id("a1")
                .role("assistant")
                .toolCalls(List.of(Message.ToolCall.builder().id("tc1").name("shell")
                        .arguments(Map.of("command", "echo hi"))
                        .build()))
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        ToolMessageMasker.MaskingResult result = masker.maskToolMessages(List.of(assistantWithCall));

        assertEquals(1, result.messages().size());
        Message flattened = result.messages().get(0);
        assertEquals("assistant", flattened.getRole());
        assertFalse(flattened.hasToolCalls());
        assertTrue(flattened.getContent().contains("masked"));
        assertFalse(result.diagnostics().isEmpty());
    }
}
