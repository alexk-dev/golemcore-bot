package me.golemcore.bot.domain.selfevolving.benchmark;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;

/**
 * Resolves judge-model selections from SelfEvolving tier settings.
 */
@Service
@RequiredArgsConstructor
public class JudgeTierResolver {

    private final RuntimeConfigService runtimeConfigService;
    private final ModelSelectionService modelSelectionService;

    public String resolveModel(String judgeLane) {
        return resolveSelection(judgeLane).model();
    }

    public ModelSelectionService.ModelSelection resolveSelection(String judgeLane) {
        return modelSelectionService.resolveExplicitTier(resolveTier(judgeLane));
    }

    private String resolveTier(String judgeLane) {
        return switch (normalizeLane(judgeLane)) {
        case "primary" -> runtimeConfigService.getSelfEvolvingJudgePrimaryTier();
        case "tiebreaker" -> runtimeConfigService.getSelfEvolvingJudgeTiebreakerTier();
        case "evolution" -> runtimeConfigService.getSelfEvolvingJudgeEvolutionTier();
        default -> throw new IllegalArgumentException("Unknown judge lane: " + judgeLane);
        };
    }

    private String normalizeLane(String judgeLane) {
        return judgeLane == null ? "" : judgeLane.trim().toLowerCase(Locale.ROOT);
    }
}
