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

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Selects and applies an L2 provider fallback from model router settings.
 */
public interface RouterFallbackSelector {

    RouterFallbackSelector NOOP = new RouterFallbackSelector() {
        @Override
        public Optional<Selection> selectNext(AgentContext context) {
            return Optional.empty();
        }

        @Override
        public Optional<Selection> selectNext(AgentContext context, Predicate<String> modelAvailable) {
            return Optional.empty();
        }

        @Override
        public void clear(AgentContext context) {
            // No state to clear.
        }
    };

    Optional<Selection> selectNext(AgentContext context);

    Optional<Selection> selectNext(AgentContext context, Predicate<String> modelAvailable);

    void clear(AgentContext context);

    record Selection(String tier, String mode, String model, String reasoning) {
    }
}
