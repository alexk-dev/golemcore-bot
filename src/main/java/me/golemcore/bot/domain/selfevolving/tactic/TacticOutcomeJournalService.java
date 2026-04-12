package me.golemcore.bot.domain.selfevolving.tactic;

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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticOutcomeEntry;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Persistent journal of tactic outcome observations. Each entry records which
 * tactic was selected for a turn and whether the turn succeeded, enabling
 * offline evaluation and feeding the quality prior loop.
 */
@Service
@Slf4j
public class TacticOutcomeJournalService {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String JOURNAL_FILE = "tactic-outcome-journal.json";
    private static final int MAX_ENTRIES = 500;

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;
    private final AtomicReference<List<TacticOutcomeEntry>> cache = new AtomicReference<>();

    public TacticOutcomeJournalService(StoragePort storagePort) {
        this.storagePort = storagePort;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public synchronized void record(TacticOutcomeEntry entry) {
        if (entry == null || StringValueSupport.isBlank(entry.getTacticId())) {
            return;
        }
        try {
            List<TacticOutcomeEntry> entries = loadEntries();
            entries.add(entry);
            if (entries.size() > MAX_ENTRIES) {
                entries = new ArrayList<>(entries.subList(entries.size() - MAX_ENTRIES, entries.size()));
            }
            String json = objectMapper.writeValueAsString(entries);
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, JOURNAL_FILE, json, true).get();
            cache.set(new ArrayList<>(entries));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.debug("[TacticOutcomeJournal] Failed to record outcome: {}", exception.getMessage());
        } catch (JsonProcessingException | ExecutionException exception) {
            log.debug("[TacticOutcomeJournal] Failed to record outcome: {}", exception.getMessage());
        }
    }

    public synchronized List<TacticOutcomeEntry> getEntries() {
        List<TacticOutcomeEntry> cached = cache.get();
        if (cached != null) {
            return new ArrayList<>(cached);
        }
        List<TacticOutcomeEntry> loaded = loadEntries();
        cache.set(new ArrayList<>(loaded));
        return loaded;
    }

    private List<TacticOutcomeEntry> loadEntries() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, JOURNAL_FILE).get();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<TacticOutcomeEntry> entries = objectMapper.readValue(json,
                    new TypeReference<>() {
                    });
            return entries != null ? new ArrayList<>(entries) : new ArrayList<>();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.debug("[TacticOutcomeJournal] Failed to load journal: {}", exception.getMessage());
            return new ArrayList<>();
        } catch (IOException | ExecutionException exception) {
            log.debug("[TacticOutcomeJournal] Failed to load journal: {}", exception.getMessage());
            return new ArrayList<>();
        }
    }
}
