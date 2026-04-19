package me.golemcore.bot.port.outbound.selfevolving;

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

import java.util.List;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaignVerdict;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCase;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkSuite;

/**
 * Outbound port for persisting and retrieving self-evolving benchmark cases,
 * suites, campaigns and their verdicts. Implementations own storage location,
 * file names, and serialization.
 */
public interface BenchmarkJournalPort {

    List<BenchmarkCase> loadCases();

    void saveCases(List<BenchmarkCase> cases);

    List<BenchmarkSuite> loadSuites();

    void saveSuites(List<BenchmarkSuite> suites);

    List<BenchmarkCampaign> loadCampaigns();

    void saveCampaigns(List<BenchmarkCampaign> campaigns);

    List<BenchmarkCampaignVerdict> loadCampaignVerdicts();

    void saveCampaignVerdicts(List<BenchmarkCampaignVerdict> verdicts);
}
