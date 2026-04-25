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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

/**
 * Stores diary entries while filtering reads by owning session id.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionDiaryService {

    private static final String AUTO_DIR = "auto";

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public void writeDiary(String sessionId, DiaryEntry entry) {
        if (entry.getTimestamp() == null) {
            entry.setTimestamp(java.time.Instant.now());
        }
        entry.setSessionId(sessionId);
        try {
            String date = LocalDate.ofInstant(entry.getTimestamp(), ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
            String path = "diary/" + date + ".jsonl";
            String line = objectMapper.writeValueAsString(entry) + "\n";
            storagePort.appendText(AUTO_DIR, path, line).join();
        } catch (Exception exception) { // NOSONAR
            log.error("[SessionDiary] Failed to write diary", exception);
        }
    }

    public List<DiaryEntry> getRecentDiary(String sessionId, int count) {
        try {
            List<DiaryEntry> entries = new ArrayList<>();
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            int normalizedCount = Math.max(1, count);
            for (int i = 0; i < 7 && entries.size() < normalizedCount; i++) {
                String date = today.minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE);
                String path = "diary/" + date + ".jsonl";
                String content = storagePort.getText(AUTO_DIR, path).join();
                if (content == null || content.isBlank()) {
                    continue;
                }
                String[] lines = content.split("\n");
                for (int j = lines.length - 1; j >= 0 && entries.size() < normalizedCount; j--) {
                    if (lines[j].isBlank()) {
                        continue;
                    }
                    DiaryEntry entry = objectMapper.readValue(lines[j], DiaryEntry.class);
                    if (sessionId.equals(entry.getSessionId())) {
                        entries.add(entry);
                    }
                }
            }
            Collections.reverse(entries);
            return entries;
        } catch (IOException | RuntimeException exception) { // NOSONAR
            log.error("[SessionDiary] Failed to read diary", exception);
            return List.of();
        }
    }
}
