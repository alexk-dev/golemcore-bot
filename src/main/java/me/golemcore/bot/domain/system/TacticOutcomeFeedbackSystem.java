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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import me.golemcore.bot.domain.selfevolving.tactic.TacticOutcomeJournalService;
import java.time.Clock;

/**
 * Compatibility hook kept in the pipeline after moving tactic outcome
 * journaling to {@link PostRunAnalysisSystem}. Leaving journaling in both
 * systems caused duplicate entries and skewed tactic quality metrics.
 */
@Component
@Order(58)
public class TacticOutcomeFeedbackSystem implements AgentSystem {

    private final RuntimeConfigService runtimeConfigService;

    public TacticOutcomeFeedbackSystem(
            RuntimeConfigService runtimeConfigService,
            TacticOutcomeJournalService tacticOutcomeJournalService,
            Clock clock) {
        this.runtimeConfigService = runtimeConfigService;
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
        return context;
    }
}
