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

package me.golemcore.bot.adapter.inbound.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.golemcore.bot.client.dto.RelevantMemoryResponse;
import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

class MemoryControllerTest {

    private MemoryComponent memoryComponent;
    private MemoryController controller;

    @BeforeEach
    void setUp() {
        memoryComponent = mock(MemoryComponent.class);
        controller = new MemoryController(memoryComponent);
    }

    @Test
    void shouldReturnRelevantItemsForActiveSession() {
        Instant now = Instant.parse("2026-04-30T10:00:00Z");
        MemoryItem item = MemoryItem.builder()
                .id("mem-1")
                .layer(MemoryItem.Layer.SEMANTIC)
                .type(MemoryItem.Type.PROJECT_FACT)
                .title("Optimizer config path")
                .content("Path is /opt/golemcore/configs/optimizer_config.yaml")
                .scope("global")
                .tags(List.of("optimizer"))
                .createdAt(now)
                .updatedAt(now)
                .references(List.of("session-a", "session-b"))
                .build();
        when(memoryComponent.queryItems(any(MemoryQuery.class))).thenReturn(List.of(item));

        StepVerifier.create(controller.getRelevantMemories("chat-123", "optimizer", 5))
                .assertNext(resp -> {
                    assertEquals(HttpStatus.OK, resp.getStatusCode());
                    RelevantMemoryResponse body = resp.getBody();
                    assertNotNull(body);
                    assertEquals("chat-123", body.getSessionId());
                    assertEquals("optimizer", body.getQueryText());
                    assertEquals(1, body.getItems().size());
                    RelevantMemoryResponse.Item dto = body.getItems().get(0);
                    assertEquals("mem-1", dto.getId());
                    assertEquals("SEMANTIC", dto.getLayer());
                    assertEquals("PROJECT_FACT", dto.getType());
                    assertEquals("Optimizer config path", dto.getTitle());
                    assertEquals(2, dto.getReferenceCount());
                })
                .verifyComplete();
    }

    @Test
    void shouldClampLimitAndIncludeSessionInScopeChain() {
        when(memoryComponent.queryItems(any(MemoryQuery.class))).thenReturn(List.of());
        ArgumentCaptor<MemoryQuery> captor = ArgumentCaptor.forClass(MemoryQuery.class);

        StepVerifier.create(controller.getRelevantMemories("chat-1", null, 99))
                .expectNextCount(1)
                .verifyComplete();

        verify(memoryComponent).queryItems(captor.capture());
        MemoryQuery query = captor.getValue();
        assertEquals(50, query.getWorkingTopK());
        assertEquals(50, query.getEpisodicTopK());
        assertEquals("chat-1", query.getScope());
        assertTrue(query.getScopeChain().contains("chat-1"));
        assertTrue(query.getScopeChain().contains("global"));
    }

    @Test
    void shouldFallBackToGlobalScopeWhenSessionMissing() {
        when(memoryComponent.queryItems(any(MemoryQuery.class))).thenReturn(List.of());
        ArgumentCaptor<MemoryQuery> captor = ArgumentCaptor.forClass(MemoryQuery.class);

        StepVerifier.create(controller.getRelevantMemories(null, null, null))
                .expectNextCount(1)
                .verifyComplete();

        verify(memoryComponent).queryItems(captor.capture());
        MemoryQuery query = captor.getValue();
        assertEquals("global", query.getScope());
        assertEquals(List.of("global"), query.getScopeChain());
        assertEquals(8, query.getSemanticTopK());
    }
}
