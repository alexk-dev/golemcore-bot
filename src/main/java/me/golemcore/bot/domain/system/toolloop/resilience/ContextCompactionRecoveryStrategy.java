package me.golemcore.bot.domain.system.toolloop.resilience;

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
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import me.golemcore.bot.domain.system.toolloop.ContextCompactionCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L4a — Context compaction as a degradation strategy for 500 errors.
 *
 * <p>
 * Some provider 500 errors correlate with large context windows — the provider
 * may silently OOM or hit internal timeouts on large payloads. This strategy
 * applies the existing context compaction mechanism (normally reserved for
 * context overflow errors) as an aggressive recovery attempt on any transient
 * server error.
 *
 * <p>
 * Applied at most once per turn to prevent compaction loops. Checks whether
 * compaction was already attempted via a context attribute flag.
 */
public class ContextCompactionRecoveryStrategy implements RecoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactionRecoveryStrategy.class);
    private static final String COMPACTION_ATTEMPTED_FLAG = "resilience.l4.compaction_attempted";

    private final ContextCompactionCoordinator compactionCoordinator;

    public ContextCompactionRecoveryStrategy(ContextCompactionCoordinator compactionCoordinator) {
        this.compactionCoordinator = compactionCoordinator;
    }

    @Override
    public String name() {
        return "context_compaction";
    }

    @Override
    public boolean isApplicable(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
        if (!config.getDegradationCompactContext()) {
            return false;
        }
        if (!LlmErrorClassifier.isTransientCode(errorCode)) {
            return false;
        }
        Boolean alreadyAttempted = context.getAttribute(COMPACTION_ATTEMPTED_FLAG);
        if (Boolean.TRUE.equals(alreadyAttempted)) {
            return false;
        }
        int messageCount = context.getMessages() != null ? context.getMessages().size() : 0;
        return messageCount > config.getDegradationCompactMinMessages();
    }

    @Override
    public RecoveryResult apply(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
        context.setAttribute(COMPACTION_ATTEMPTED_FLAG, true);
        int beforeCount = context.getMessages() != null ? context.getMessages().size() : 0;
        boolean compacted = compactionCoordinator.recoverFromContextOverflow(context, 0, 0);
        int afterCount = context.getMessages() != null ? context.getMessages().size() : 0;
        if (compacted) {
            log.info("[Resilience] L4 context compaction applied: {} → {} messages", beforeCount, afterCount);
            return RecoveryResult.success("compacted " + beforeCount + " → " + afterCount + " messages");
        }
        return RecoveryResult.notApplicable("compaction coordinator declined");
    }
}
