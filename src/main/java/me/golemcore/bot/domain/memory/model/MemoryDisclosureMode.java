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
 * Supported progressive-disclosure modes for prompt-facing memory assembly.
 */
public enum MemoryDisclosureMode {
    INDEX("index"), SUMMARY("summary"), SELECTIVE_DETAIL("selective_detail"), FULL_PACK("full_pack");

    private final String configValue;

    MemoryDisclosureMode(String configValue) {
        this.configValue = configValue;
    }

    /**
     * Parse a runtime-config string into a supported disclosure mode.
     *
     * @param value
     *            runtime-config value
     * @return parsed mode, defaulting to {@link #SUMMARY}
     */
    public static MemoryDisclosureMode fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return SUMMARY;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MemoryDisclosureMode mode : values()) {
            if (mode.configValue.equals(normalized)) {
                return mode;
            }
        }
        return SUMMARY;
    }

    /**
     * @return stable runtime-config value for this mode
     */
    public String getConfigValue() {
        return configValue;
    }
}
