package me.golemcore.bot.domain.service;

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

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.model.Memory;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing persistent agent memory across conversations. Implements
 * {@link MemoryComponent} to provide access to long-term memory (MEMORY.md),
 * daily notes, and recent conversation context. Memory content is injected into
 * the system prompt to maintain continuity and context awareness.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryService implements MemoryComponent {

    private final StoragePort storagePort;
    private final BotProperties properties;
    private final RuntimeConfigService runtimeConfigService;

    private static final String LONG_TERM_KEY = "MEMORY.md";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override
    public String getComponentType() {
        return "memory";
    }

    @Override
    public Memory getMemory() {
        return Memory.builder()
                .longTermContent(readLongTerm())
                .todayNotes(readToday())
                .recentDays(getRecentDays())
                .build();
    }

    @Override
    public String readLongTerm() {
        if (!runtimeConfigService.isMemoryEnabled()) {
            return "";
        }
        try {
            return storagePort.getText(getMemoryDirectory(), LONG_TERM_KEY).join();
        } catch (Exception e) {
            log.debug("No long-term memory found");
            return "";
        }
    }

    @Override
    public void writeLongTerm(String content) {
        if (!runtimeConfigService.isMemoryEnabled()) {
            return;
        }
        try {
            storagePort.putText(getMemoryDirectory(), LONG_TERM_KEY, content).join();
            log.debug("Updated long-term memory");
        } catch (Exception e) {
            log.error("Failed to write long-term memory", e);
        }
    }

    @Override
    public String readToday() {
        if (!runtimeConfigService.isMemoryEnabled()) {
            return "";
        }
        String key = getTodayKey();
        try {
            return storagePort.getText(getMemoryDirectory(), key).join();
        } catch (Exception e) {
            log.debug("No notes for today");
            return "";
        }
    }

    @Override
    public void appendToday(String entry) {
        if (!runtimeConfigService.isMemoryEnabled()) {
            return;
        }
        String key = getTodayKey();
        try {
            storagePort.appendText(getMemoryDirectory(), key, entry).join();
        } catch (Exception e) {
            log.error("Failed to append to today's notes", e);
        }
    }

    @Override
    public String getMemoryContext() {
        return getMemory().toContext();
    }

    private List<Memory.DailyNote> getRecentDays() {
        List<Memory.DailyNote> notes = new ArrayList<>();
        if (!runtimeConfigService.isMemoryEnabled()) {
            return notes;
        }
        int recentDays = runtimeConfigService.getMemoryRecentDays();

        for (int i = 1; i <= recentDays; i++) {
            LocalDate date = LocalDate.now().minusDays(i);
            String key = date.format(DATE_FORMAT) + ".md";

            try {
                String content = storagePort.getText(getMemoryDirectory(), key).join();
                if (content != null && !content.isBlank()) {
                    notes.add(Memory.DailyNote.builder()
                            .date(date)
                            .content(content)
                            .build());
                }
            } catch (RuntimeException e) {
                log.trace("No memory file for date {}, skipping", date);
            }
        }

        return notes;
    }

    private String getTodayKey() {
        return LocalDate.now().format(DATE_FORMAT) + ".md";
    }

    private String getMemoryDirectory() {
        String configured = properties.getMemory().getDirectory();
        if (configured == null || configured.isBlank()) {
            return "memory";
        }
        return configured;
    }
}
