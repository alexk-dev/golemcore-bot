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
import me.golemcore.bot.port.outbound.ModelSelectionQueryPort;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;

/**
 * Resolves judge-model selections from SelfEvolving tier settings.
 */
@Service
@RequiredArgsConstructor
public class JudgeTierResolver {

    private final SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private final ModelSelectionQueryPort modelSelectionQueryPort;

    public String resolveModel(String judgeLane) {
        return resolveSelection(judgeLane).model();
    }

    public ModelSelectionQueryPort.ModelSelection resolveSelection(String judgeLane) {
        return modelSelectionQueryPort.resolveExplicitSelection(resolveTier(judgeLane));
    }

    private String resolveTier(String judgeLane) {
        return switch (normalizeLane(judgeLane)) {
        case "primary" -> runtimeConfigPort.getSelfEvolvingJudgePrimaryTier();
        case "tiebreaker" -> runtimeConfigPort.getSelfEvolvingJudgeTiebreakerTier();
        case "evolution" -> runtimeConfigPort.getSelfEvolvingJudgeEvolutionTier();
        default -> throw new IllegalArgumentException("Unknown judge lane: " + judgeLane);
        };
    }

    private String normalizeLane(String judgeLane) {
        return judgeLane == null ? "" : judgeLane.trim().toLowerCase(Locale.ROOT);
    }
}
