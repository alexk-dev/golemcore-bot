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

package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Tool for switching the model tier during a conversation.
 *
 * <p>
 * The LLM calls this tool to switch to a different model tier (balanced, smart,
 * coding, deep) when it determines the task requires different capabilities.
 * Respects the user's tier force setting — if force is enabled, the tool
 * returns an error.
 *
 * <p>
 * Uses {@link AgentContextHolder} to modify the current {@link AgentContext}.
 * Must NOT use {@code CompletableFuture.supplyAsync()} (ThreadLocal not
 * propagated).
 *
 * @see me.golemcore.bot.domain.loop.AgentContextHolder
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TierTool implements ToolComponent {

    private static final Set<String> VALID_TIERS = Set.of("balanced", "smart", "coding", "deep");

    private final UserPreferencesService userPreferencesService;
    private final BotProperties properties;

    @Override
    public String getToolName() {
        return "set_tier";
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("set_tier")
                .description("Switch the model tier for the current conversation. "
                        + "Available tiers: balanced (general), smart (complex reasoning), "
                        + "coding (programming), deep (PhD-level analysis).")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "tier", Map.of(
                                        "type", "string",
                                        "enum", List.of("balanced", "smart", "coding", "deep"),
                                        "description", "The model tier to switch to")),
                        "required", List.of("tier")))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        String tier = (String) parameters.get("tier");

        if (tier == null || tier.isBlank()) {
            return CompletableFuture.completedFuture(ToolResult.failure("tier is required"));
        }

        if (!VALID_TIERS.contains(tier)) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Invalid tier: " + tier + ". Valid tiers: balanced, smart, coding, deep"));
        }

        if (userPreferencesService.getPreferences().isTierForce()) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Tier is locked by user. Use /tier command to unlock."));
        }

        AgentContext context = AgentContextHolder.get();
        if (context == null) {
            return CompletableFuture.completedFuture(ToolResult.failure("No agent context available"));
        }

        String previousTier = context.getModelTier();
        context.setModelTier(tier);

        log.info("[TierTool] Tier switched: {} -> {}", previousTier, tier);

        return CompletableFuture.completedFuture(
                ToolResult.success("Model tier switched to: " + tier));
    }

    @Override
    public boolean isEnabled() {
        return properties.getTools().getTier().isEnabled();
    }
}
