package me.golemcore.bot.domain.system;

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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticOutcomeEntry;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.selfevolving.tactic.TacticOutcomeJournalService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Records tactic outcome feedback after every turn where a tactic was selected.
 * Runs after TurnOutcomeFinalizationSystem (order 57) so that TurnOutcome is
 * available, and before ResponseRoutingSystem (order 60).
 */
@Component
@Order(58)
@Slf4j
public class TacticOutcomeFeedbackSystem implements AgentSystem {

    private final RuntimeConfigService runtimeConfigService;
    private final TacticOutcomeJournalService tacticOutcomeJournalService;
    private final Clock clock;

    public TacticOutcomeFeedbackSystem(
            RuntimeConfigService runtimeConfigService,
            TacticOutcomeJournalService tacticOutcomeJournalService,
            Clock clock) {
        this.runtimeConfigService = runtimeConfigService;
        this.tacticOutcomeJournalService = tacticOutcomeJournalService;
        this.clock = clock;
    }

    @Override
    public String getName() {
        return "TacticOutcomeFeedbackSystem";
    }

    @Override
    public int getOrder() {
        return 58;
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        return context != null
                && context.getTurnOutcome() != null
                && context.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION) != null;
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService != null && runtimeConfigService.isSelfEvolvingEnabled();
    }

    @Override
    public AgentContext process(AgentContext context) {
        try {
            TacticSearchResult selectedTactic = context.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION);
            TacticSearchQuery query = context.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY);
            TurnOutcome turnOutcome = context.getTurnOutcome();

            String searchMode = selectedTactic.getExplanation() != null
                    ? selectedTactic.getExplanation().getSearchMode()
                    : null;
            Double finalScore = selectedTactic.getExplanation() != null
                    ? selectedTactic.getExplanation().getFinalScore()
                    : null;

            TacticOutcomeEntry entry = TacticOutcomeEntry.builder()
                    .tacticId(selectedTactic.getTacticId())
                    .rawQuery(query != null ? query.getRawQuery() : null)
                    .queryViews(query != null ? query.getQueryViews() : null)
                    .searchMode(searchMode)
                    .finalScore(finalScore)
                    .finishReason(turnOutcome.getFinishReason() != null
                            ? turnOutcome.getFinishReason().name().toLowerCase()
                            : null)
                    .recordedAt(Instant.now(clock))
                    .build();

            tacticOutcomeJournalService.record(entry);
        } catch (RuntimeException exception) { // NOSONAR - feedback must not break the pipeline
            log.debug("[TacticOutcomeFeedback] Failed to record tactic outcome: {}", exception.getMessage());
        }
        return context;
    }
}
