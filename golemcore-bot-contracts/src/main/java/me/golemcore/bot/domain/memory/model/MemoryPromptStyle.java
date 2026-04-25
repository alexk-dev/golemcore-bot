package me.golemcore.bot.domain.memory.model;

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

import java.util.Locale;

/**
 * Rendering density for prompt-facing memory output.
 */
public enum MemoryPromptStyle {
    COMPACT("compact"), BALANCED("balanced"), RICH("rich");

    private final String configValue;

    MemoryPromptStyle(String configValue) {
        this.configValue = configValue;
    }

    /**
     * Parse a runtime-config string into a prompt style.
     *
     * @param value
     *            runtime-config value
     * @return parsed style, defaulting to {@link #BALANCED}
     */
    public static MemoryPromptStyle fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return BALANCED;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MemoryPromptStyle style : values()) {
            if (style.configValue.equals(normalized)) {
                return style;
            }
        }
        return BALANCED;
    }

    /**
     * @return stable runtime-config value for this style
     */
    public String getConfigValue() {
        return configValue;
    }
}
