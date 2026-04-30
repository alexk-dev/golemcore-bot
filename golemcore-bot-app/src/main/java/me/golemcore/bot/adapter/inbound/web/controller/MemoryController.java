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

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.client.dto.RelevantMemoryResponse;
import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryQuery;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only access to Memory V2 records relevant to the active conversation.
 * Powers the Inspector "Memory" tab in the dashboard harness.
 */
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 50;

    private final MemoryComponent memoryComponent;

    @GetMapping("/relevant")
    public Mono<ResponseEntity<RelevantMemoryResponse>> getRelevantMemories(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer limit) {
        int effectiveLimit = clampLimit(limit);
        String safeQuery = query != null ? query : "";
        String safeSessionId = sessionId != null ? sessionId : "";

        MemoryQuery memoryQuery = MemoryQuery.builder()
                .queryText(safeQuery)
                .scope(safeSessionId.isBlank() ? "global" : safeSessionId)
                .scopeChain(buildScopeChain(safeSessionId))
                .workingTopK(effectiveLimit)
                .episodicTopK(effectiveLimit)
                .semanticTopK(effectiveLimit)
                .proceduralTopK(effectiveLimit)
                .build();

        List<MemoryItem> items = memoryComponent.queryItems(memoryQuery);
        List<RelevantMemoryResponse.Item> dtoItems = items.stream()
                .limit(effectiveLimit)
                .map(MemoryController::toDto)
                .toList();

        RelevantMemoryResponse response = RelevantMemoryResponse.builder()
                .items(new ArrayList<>(dtoItems))
                .sessionId(safeSessionId)
                .queryText(safeQuery)
                .build();
        return Mono.just(ResponseEntity.ok(response));
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static List<String> buildScopeChain(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.singletonList("global");
        }
        List<String> chain = new ArrayList<>(2);
        chain.add(sessionId);
        chain.add("global");
        return chain;
    }

    private static RelevantMemoryResponse.Item toDto(MemoryItem item) {
        List<String> tags = item.getTags() == null
                ? new ArrayList<>()
                : new ArrayList<>(item.getTags());
        List<String> references = item.getReferences() == null
                ? new ArrayList<>()
                : new ArrayList<>(item.getReferences());
        return RelevantMemoryResponse.Item.builder()
                .id(item.getId())
                .layer(item.getLayer() != null ? item.getLayer().name() : null)
                .type(item.getType() != null ? item.getType().name() : null)
                .title(item.getTitle())
                .content(item.getContent())
                .scope(item.getScope())
                .tags(tags)
                .source(item.getSource())
                .confidence(item.getConfidence())
                .salience(item.getSalience())
                .ttlDays(item.getTtlDays())
                .createdAt(formatInstant(item.getCreatedAt()))
                .updatedAt(formatInstant(item.getUpdatedAt()))
                .lastAccessedAt(formatInstant(item.getLastAccessedAt()))
                .references(references)
                .referenceCount(references.size())
                .build();
    }

    private static String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
