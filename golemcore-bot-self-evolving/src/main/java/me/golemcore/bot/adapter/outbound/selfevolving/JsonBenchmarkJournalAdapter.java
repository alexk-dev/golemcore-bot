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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaignVerdict;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCase;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkSuite;
import me.golemcore.bot.domain.support.StringValueSupport;
import me.golemcore.bot.port.outbound.StoragePort;
import me.golemcore.bot.port.outbound.selfevolving.BenchmarkJournalPort;
import org.springframework.stereotype.Component;

/**
 * JSON-on-StoragePort adapter for the self-evolving benchmark journal. Owns
 * directory layout, file names, and the Jackson mapper.
 */
@Component
@Slf4j
public class JsonBenchmarkJournalAdapter implements BenchmarkJournalPort {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String CASES_FILE = "benchmark-cases.json";
    private static final String SUITES_FILE = "benchmark-suites.json";
    private static final String CAMPAIGNS_FILE = "benchmark-campaigns.json";
    private static final String CAMPAIGN_VERDICTS_FILE = "benchmark-campaign-verdicts.json";
    private static final TypeReference<List<BenchmarkCase>> CASE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<BenchmarkSuite>> SUITE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<BenchmarkCampaign>> CAMPAIGN_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<BenchmarkCampaignVerdict>> CAMPAIGN_VERDICT_LIST_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public JsonBenchmarkJournalAdapter(StoragePort storagePort) {
        this.storagePort = storagePort;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public List<BenchmarkCase> loadCases() {
        return loadList(CASES_FILE, CASE_LIST_TYPE, "cases");
    }

    @Override
    public void saveCases(List<BenchmarkCase> cases) {
        saveList(CASES_FILE, cases, "cases");
    }

    @Override
    public List<BenchmarkSuite> loadSuites() {
        return loadList(SUITES_FILE, SUITE_LIST_TYPE, "suites");
    }

    @Override
    public void saveSuites(List<BenchmarkSuite> suites) {
        saveList(SUITES_FILE, suites, "suites");
    }

    @Override
    public List<BenchmarkCampaign> loadCampaigns() {
        return loadList(CAMPAIGNS_FILE, CAMPAIGN_LIST_TYPE, "campaigns");
    }

    @Override
    public void saveCampaigns(List<BenchmarkCampaign> campaigns) {
        saveList(CAMPAIGNS_FILE, campaigns, "campaigns");
    }

    @Override
    public List<BenchmarkCampaignVerdict> loadCampaignVerdicts() {
        return loadList(CAMPAIGN_VERDICTS_FILE, CAMPAIGN_VERDICT_LIST_TYPE, "campaign verdicts");
    }

    @Override
    public void saveCampaignVerdicts(List<BenchmarkCampaignVerdict> verdicts) {
        saveList(CAMPAIGN_VERDICTS_FILE, verdicts, "campaign verdicts");
    }

    private <T> List<T> loadList(String fileName, TypeReference<List<T>> typeRef, String label) {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, fileName).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<T> items = objectMapper.readValue(json, typeRef);
            return items != null ? new ArrayList<>(items) : new ArrayList<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load benchmark {}: {}", label, e.getMessage());
            return new ArrayList<>();
        }
    }

    private <T> void saveList(String fileName, List<T> items, String label) {
        try {
            String json = objectMapper.writeValueAsString(items);
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, fileName, json, true).join();
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist benchmark " + label, e);
        }
    }
}
