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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCase;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkSuite;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Harvests production runs into benchmark artifacts.
 */
@Service
@Slf4j
public class BenchmarkLabService {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String CASES_FILE = "benchmark-cases.json";
    private static final String SUITES_FILE = "benchmark-suites.json";
    private static final String CAMPAIGNS_FILE = "benchmark-campaigns.json";
    private static final TypeReference<List<BenchmarkCase>> CASE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<BenchmarkSuite>> SUITE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<BenchmarkCampaign>> CAMPAIGN_LIST_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final SelfEvolvingRunService selfEvolvingRunService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AtomicReference<List<BenchmarkCase>> caseCache = new AtomicReference<>();
    private final AtomicReference<List<BenchmarkSuite>> suiteCache = new AtomicReference<>();
    private final AtomicReference<List<BenchmarkCampaign>> campaignCache = new AtomicReference<>();

    public BenchmarkLabService(StoragePort storagePort, SelfEvolvingRunService selfEvolvingRunService) {
        this(storagePort, selfEvolvingRunService, Clock.systemUTC());
    }

    BenchmarkLabService(StoragePort storagePort, SelfEvolvingRunService selfEvolvingRunService, Clock clock) {
        this.storagePort = storagePort;
        this.selfEvolvingRunService = selfEvolvingRunService;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public BenchmarkCampaign createRegressionCampaign(String runId) {
        RunRecord runRecord = selfEvolvingRunService.getRuns().stream()
                .filter(run -> run != null && runId.equals(run.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        String suiteId = UUID.randomUUID().toString();
        BenchmarkCase benchmarkCase = BenchmarkCase.builder()
                .id(UUID.randomUUID().toString())
                .suiteId(suiteId)
                .sourceRunId(runRecord.getId())
                .name("Harvested run " + runRecord.getId())
                .description("Regression case harvested from run " + runRecord.getId())
                .scoringContract("regression")
                .createdAt(Instant.now(clock))
                .inputPayload(buildInputPayload(runRecord))
                .expectedConstraints(buildExpectedConstraints(runRecord))
                .build();
        BenchmarkSuite benchmarkSuite = BenchmarkSuite.builder()
                .id(suiteId)
                .name("Regression suite " + runRecord.getId())
                .description("Harvested regression suite for run " + runRecord.getId())
                .taskFamily("harvested")
                .createdAt(Instant.now(clock))
                .caseIds(List.of(benchmarkCase.getId()))
                .build();
        BenchmarkCampaign benchmarkCampaign = BenchmarkCampaign.builder()
                .id(UUID.randomUUID().toString())
                .suiteId(suiteId)
                .baselineBundleId(runRecord.getArtifactBundleId())
                .candidateBundleId(runRecord.getArtifactBundleId())
                .status("created")
                .startedAt(Instant.now(clock))
                .runIds(List.of(runRecord.getId()))
                .build();

        saveCase(benchmarkCase);
        saveSuite(benchmarkSuite);
        saveCampaign(benchmarkCampaign);
        return benchmarkCampaign;
    }

    public List<BenchmarkSuite> getSuites() {
        List<BenchmarkSuite> cached = suiteCache.get();
        if (cached == null) {
            cached = loadSuites();
            suiteCache.set(cached);
        }
        return cached;
    }

    public List<BenchmarkCampaign> getCampaigns() {
        List<BenchmarkCampaign> cached = campaignCache.get();
        if (cached == null) {
            cached = loadCampaigns();
            campaignCache.set(cached);
        }
        return cached;
    }

    private List<BenchmarkCase> getCases() {
        List<BenchmarkCase> cached = caseCache.get();
        if (cached == null) {
            cached = loadCases();
            caseCache.set(cached);
        }
        return cached;
    }

    private Map<String, Object> buildInputPayload(RunRecord runRecord) {
        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("runId", runRecord.getId());
        inputPayload.put("traceId", runRecord.getTraceId());
        inputPayload.put("golemId", runRecord.getGolemId());
        inputPayload.put("status", runRecord.getStatus());
        return inputPayload;
    }

    private Map<String, Object> buildExpectedConstraints(RunRecord runRecord) {
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("baselineBundleId", runRecord.getArtifactBundleId());
        constraints.put("baselineStatus", runRecord.getStatus());
        return constraints;
    }

    private void saveCase(BenchmarkCase benchmarkCase) {
        List<BenchmarkCase> cases = new ArrayList<>(getCases());
        cases.add(benchmarkCase);
        saveCases(cases);
    }

    private void saveSuite(BenchmarkSuite benchmarkSuite) {
        List<BenchmarkSuite> suites = new ArrayList<>(getSuites());
        suites.add(benchmarkSuite);
        saveSuites(suites);
    }

    private void saveCampaign(BenchmarkCampaign benchmarkCampaign) {
        List<BenchmarkCampaign> campaigns = new ArrayList<>(getCampaigns());
        campaigns.add(benchmarkCampaign);
        saveCampaigns(campaigns);
    }

    private List<BenchmarkCase> loadCases() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, CASES_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<BenchmarkCase> cases = objectMapper.readValue(json, CASE_LIST_TYPE);
            return cases != null ? new ArrayList<>(cases) : new ArrayList<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load benchmark cases: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<BenchmarkSuite> loadSuites() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, SUITES_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<BenchmarkSuite> suites = objectMapper.readValue(json, SUITE_LIST_TYPE);
            return suites != null ? new ArrayList<>(suites) : new ArrayList<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load benchmark suites: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<BenchmarkCampaign> loadCampaigns() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, CAMPAIGNS_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<BenchmarkCampaign> campaigns = objectMapper.readValue(json, CAMPAIGN_LIST_TYPE);
            return campaigns != null ? new ArrayList<>(campaigns) : new ArrayList<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load benchmark campaigns: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void saveCases(List<BenchmarkCase> cases) {
        try {
            String json = objectMapper.writeValueAsString(cases);
            storagePort.putText(SELF_EVOLVING_DIR, CASES_FILE, json).join();
            caseCache.set(new ArrayList<>(cases));
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist benchmark cases", e);
        }
    }

    private void saveSuites(List<BenchmarkSuite> suites) {
        try {
            String json = objectMapper.writeValueAsString(suites);
            storagePort.putText(SELF_EVOLVING_DIR, SUITES_FILE, json).join();
            suiteCache.set(new ArrayList<>(suites));
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist benchmark suites", e);
        }
    }

    private void saveCampaigns(List<BenchmarkCampaign> campaigns) {
        try {
            String json = objectMapper.writeValueAsString(campaigns);
            storagePort.putText(SELF_EVOLVING_DIR, CAMPAIGNS_FILE, json).join();
            campaignCache.set(new ArrayList<>(campaigns));
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist benchmark campaigns", e);
        }
    }
}
