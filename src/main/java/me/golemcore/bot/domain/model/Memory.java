package me.golemcore.bot.domain.model;

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

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Represents agent memory including long-term knowledge and daily notes. Memory
 * is loaded from the workspace and injected into system prompts to provide
 * continuity across conversations.
 */
@Data
@Builder
public class Memory {

    private String longTermContent; // MEMORY.md content
    private String todayNotes; // Today's notes
    private List<DailyNote> recentDays; // Recent N days

    /**
     * Represents a single day's notes.
     */
    @Data
    @Builder
    public static class DailyNote {
        private LocalDate date;
        private String content;
    }

    /**
     * Formats all memory content into a markdown string for inclusion in system
     * prompts.
     */
    public String toContext() {
        StringBuilder sb = new StringBuilder();

        if (longTermContent != null && !longTermContent.isBlank()) {
            sb.append("## Long-term Memory\n");
            sb.append(longTermContent);
            sb.append("\n\n");
        }

        if (todayNotes != null && !todayNotes.isBlank()) {
            sb.append("## Today's Notes\n");
            sb.append(todayNotes);
            sb.append("\n\n");
        }

        if (recentDays != null && !recentDays.isEmpty()) {
            sb.append("## Recent Context\n");
            for (DailyNote note : recentDays) {
                sb.append("### ").append(note.getDate()).append("\n");
                sb.append(note.getContent()).append("\n\n");
            }
        }

        return sb.toString().trim();
    }
}
