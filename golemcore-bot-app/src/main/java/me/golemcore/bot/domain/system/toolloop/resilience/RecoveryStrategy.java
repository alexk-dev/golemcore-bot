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
import me.golemcore.bot.domain.model.RuntimeConfig;

/**
 * L4 — Graceful degradation strategy interface.
 *
 * <p>
 * When all providers are failing and hot retry is exhausted, the orchestrator
 * tries to reduce request complexity so a simpler request might succeed where
 * the original failed. Each strategy modifies the AgentContext in place
 * (compaction, model downgrade, tool stripping) and reports whether the
 * modification was applied.
 *
 * <p>
 * Strategies are evaluated in order. The first one that succeeds triggers a
 * retry; if none succeed, the orchestrator falls through to L5 (cold retry).
 */
public interface RecoveryStrategy {

    /**
     * Human-readable name for logging (e.g., "context_compaction",
     * "model_downgrade").
     */
    String name();

    /**
     * Returns true if this strategy is applicable given the current error and
     * context.
     */
    boolean isApplicable(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config);

    /**
     * Attempts to apply the recovery strategy by mutating the context.
     *
     * @return result indicating whether recovery was applied
     */
    RecoveryResult apply(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config);

    record RecoveryResult(boolean recovered, String detail) {
        public static RecoveryResult success(String detail) {
            return new RecoveryResult(true, detail);
        }

        public static RecoveryResult notApplicable(String reason) {
            return new RecoveryResult(false, reason);
        }
    }
}
