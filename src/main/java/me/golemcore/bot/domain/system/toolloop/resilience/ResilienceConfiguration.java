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

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.service.TurnProgressService;
import me.golemcore.bot.domain.system.toolloop.ContextCompactionCoordinator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * Spring configuration for the LLM resilience layer.
 *
 * <p>
 * Wires together all five defense layers and exposes a single
 * {@link LlmResilienceOrchestrator} bean for injection into the tool loop.
 */
@Configuration
public class ResilienceConfiguration {

    @Bean
    static LlmRetryPolicy llmRetryPolicy() {
        return new LlmRetryPolicy();
    }

    @Bean
    static ProviderCircuitBreaker providerCircuitBreaker(Clock clock, RuntimeConfigService configService) {
        RuntimeConfig.ResilienceConfig config = configService.getResilienceConfig();
        return new ProviderCircuitBreaker(
                clock,
                config.getCircuitBreakerFailureThreshold(),
                config.getCircuitBreakerWindowSeconds(),
                config.getCircuitBreakerOpenDurationSeconds());
    }

    @Bean
    static ProviderFallbackSelector providerFallbackSelector(RuntimeConfigService runtimeConfigService) {
        return new ProviderFallbackSelector(runtimeConfigService);
    }

    @Bean
    static ContextCompactionCoordinator contextCompactionCoordinator(
            ContextCompactionPolicy contextCompactionPolicy,
            CompactionOrchestrationService compactionOrchestrationService,
            RuntimeEventService runtimeEventService,
            TurnProgressService turnProgressService) {
        return new ContextCompactionCoordinator(
                contextCompactionPolicy,
                compactionOrchestrationService,
                runtimeEventService,
                turnProgressService);
    }

    @Bean
    static ContextCompactionRecoveryStrategy contextCompactionRecoveryStrategy(
            ContextCompactionCoordinator compactionCoordinator) {
        return new ContextCompactionRecoveryStrategy(compactionCoordinator);
    }

    @Bean
    static ModelDowngradeRecoveryStrategy modelDowngradeRecoveryStrategy() {
        return new ModelDowngradeRecoveryStrategy();
    }

    @Bean
    static ToolStripRecoveryStrategy toolStripRecoveryStrategy() {
        return new ToolStripRecoveryStrategy();
    }

    @Bean
    static SuspendedTurnManager suspendedTurnManager(DelayedSessionActionService actionService, Clock clock) {
        return new SuspendedTurnManager(actionService, clock);
    }

    @Bean
    static LlmResilienceOrchestrator llmResilienceOrchestrator(
            LlmRetryPolicy retryPolicy,
            ProviderCircuitBreaker circuitBreaker,
            ProviderFallbackSelector providerFallbackSelector,
            ContextCompactionRecoveryStrategy contextCompaction,
            ModelDowngradeRecoveryStrategy modelDowngrade,
            ToolStripRecoveryStrategy toolStrip,
            SuspendedTurnManager suspendedTurnManager) {
        return new LlmResilienceOrchestrator(
                retryPolicy,
                circuitBreaker,
                providerFallbackSelector,
                List.of(contextCompaction, modelDowngrade, toolStrip),
                suspendedTurnManager);
    }
}
