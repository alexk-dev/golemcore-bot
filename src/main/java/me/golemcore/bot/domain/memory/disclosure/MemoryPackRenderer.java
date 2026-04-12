package me.golemcore.bot.domain.memory.disclosure;

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

import me.golemcore.bot.domain.memory.model.MemoryPackSection;
import me.golemcore.bot.domain.memory.model.MemoryPromptStyle;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Renders structured memory sections into prompt-ready text.
 */
@Service
public class MemoryPackRenderer {

    /**
     * Render prompt-ready memory text from structured sections.
     *
     * @param sections
     *            sections to render
     * @param promptStyle
     *            rendering density
     * @return prompt-ready memory text
     */
    public String render(List<MemoryPackSection> sections, MemoryPromptStyle promptStyle) {
        if (sections == null || sections.isEmpty()) {
            return "";
        }
        String separator = promptStyle == MemoryPromptStyle.COMPACT ? "\n" : "\n\n";
        StringBuilder sb = new StringBuilder();
        for (MemoryPackSection section : sections) {
            if (section == null || section.getLines() == null || section.getLines().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append("## ").append(section.getTitle()).append("\n");
            for (String line : section.getLines()) {
                sb.append("- ").append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
