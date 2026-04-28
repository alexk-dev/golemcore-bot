package me.golemcore.bot.adapter.outbound.selfevolving;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.support.StringValueSupport;
import me.golemcore.bot.port.outbound.StoragePort;
import me.golemcore.bot.port.outbound.selfevolving.RunJournalPort;
import org.springframework.stereotype.Component;

/**
 * JSON-on-StoragePort adapter for the self-evolving run journal. Owns the
 * directory layout, file names, and verdict wire-format (object or legacy
 * array).
 */
@Component
@Slf4j
public class JsonRunJournalAdapter implements RunJournalPort {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String RUNS_FILE = "runs.json";
    private static final String RUN_VERDICTS_FILE = "run-verdicts.json";
    private static final TypeReference<List<RunRecord>> RUN_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, RunVerdict>> RUN_VERDICT_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<RunVerdict>> RUN_VERDICT_LIST_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public JsonRunJournalAdapter(StoragePort storagePort) {
        this.storagePort = storagePort;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public List<RunRecord> loadRuns() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, RUNS_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<RunRecord> runs = objectMapper.readValue(json, RUN_LIST_TYPE);
            return runs != null ? new ArrayList<>(runs) : new ArrayList<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load runs: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void saveRuns(List<RunRecord> runs) {
        try {
            String json = objectMapper.writeValueAsString(runs);
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, RUNS_FILE, json, true).join();
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist self-evolving runs", e);
        }
    }

    @Override
    public Map<String, RunVerdict> loadVerdicts() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, RUN_VERDICTS_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new LinkedHashMap<>();
            }
            JsonNode root = objectMapper.readTree(json);
            if (root == null || root.isNull()) {
                return new LinkedHashMap<>();
            }
            if (root.isObject()) {
                Map<String, RunVerdict> verdicts = objectMapper.convertValue(root, RUN_VERDICT_MAP_TYPE);
                return verdicts != null ? new LinkedHashMap<>(verdicts) : new LinkedHashMap<>();
            }
            if (root.isArray()) {
                List<RunVerdict> verdicts = objectMapper.convertValue(root, RUN_VERDICT_LIST_TYPE);
                Map<String, RunVerdict> byRunId = new LinkedHashMap<>();
                if (verdicts != null) {
                    for (RunVerdict verdict : verdicts) {
                        if (verdict == null || StringValueSupport.isBlank(verdict.getRunId())) {
                            continue;
                        }
                        byRunId.put(verdict.getRunId(), verdict);
                    }
                }
                return byRunId;
            }
            return new LinkedHashMap<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load run verdicts: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @Override
    public void saveVerdicts(Map<String, RunVerdict> verdicts) {
        try {
            String json = objectMapper.writeValueAsString(verdicts);
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, RUN_VERDICTS_FILE, json, true).join();
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist self-evolving run verdicts", e);
        }
    }

    @Override
    public String exportRunsAsJson(List<RunRecord> runs) {
        try {
            return objectMapper.writeValueAsString(runs);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize runs", e);
        }
    }
}
