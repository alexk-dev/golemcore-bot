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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a modular section of the system prompt loaded from markdown files.
 * Sections can be ordered, enabled/disabled, and contain template variables.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptSection {

    private String name; // derived from filename: IDENTITY.md → "identity"
    private String content; // markdown body (after frontmatter)
    private String description; // from frontmatter

    @Builder.Default
    private int order = 100;

    @Builder.Default
    private boolean enabled = true;
}
