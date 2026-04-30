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

package me.golemcore.bot.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Dashboard payload for the Inspector Memory tab. Each item describes a Memory
 * V2 record that is potentially relevant to the active conversation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelevantMemoryResponse {

    @Builder.Default
    private List<Item> items = new ArrayList<>();

    private String sessionId;
    private String queryText;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String id;
        private String layer;
        private String type;
        private String title;
        private String content;
        private String scope;

        @Builder.Default
        private List<String> tags = new ArrayList<>();

        private String source;
        private Double confidence;
        private Double salience;
        private Integer ttlDays;
        private String createdAt;
        private String updatedAt;
        private String lastAccessedAt;

        @Builder.Default
        private List<String> references = new ArrayList<>();

        private int referenceCount;
    }
}
