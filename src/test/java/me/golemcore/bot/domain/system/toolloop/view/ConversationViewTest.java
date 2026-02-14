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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationViewTest {

    @Test
    void constructorShouldDefensivelyCopyLists() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role("user").content("hi").build());

        List<String> diagnostics = new ArrayList<>();
        diagnostics.add("d1");

        ConversationView view = new ConversationView(messages, diagnostics);

        messages.add(Message.builder().role("user").content("later").build());
        diagnostics.add("d2");

        assertEquals(1, view.messages().size());
        assertEquals(1, view.diagnostics().size());

        assertThrows(UnsupportedOperationException.class, () -> view.messages().add(
                Message.builder().role("user").content("x").build()));
        assertThrows(UnsupportedOperationException.class, () -> view.diagnostics().add("x"));
    }

    @Test
    void shouldDefaultNullListsToEmpty() {
        ConversationView view = new ConversationView(null, null);
        assertNotNull(view.messages());
        assertNotNull(view.diagnostics());
        assertTrue(view.messages().isEmpty());
        assertTrue(view.diagnostics().isEmpty());
    }

    @Test
    void ofMessagesShouldUseEmptyDiagnostics() {
        ConversationView view = ConversationView.ofMessages(List.of(
                Message.builder().role("user").content("hi").build()));

        assertEquals(1, view.messages().size());
        assertTrue(view.diagnostics().isEmpty());
    }
}
