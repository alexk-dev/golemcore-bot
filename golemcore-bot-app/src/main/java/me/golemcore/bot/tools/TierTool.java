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
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.sessions.SessionModelSettingsSupport;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool for switching the model tier during a conversation.
 *
 * <p>
 * The LLM calls this tool to switch to a different model tier when it
 * determines the task requires different capabilities. Respects the user's tier
 * force setting — if force is enabled, the tool returns an error.
 *
 * <p>
 * Uses {@link AgentContextHolder} to modify the current {@link AgentContext}.
 * Must NOT use {@code CompletableFuture.supplyAsync()} (ThreadLocal not
 * propagated).
 *
 * @see me.golemcore.bot.domain.loop.AgentContextHolder
 */
@Component
@Slf4j
public class TierTool implements ToolComponent {

    private final UserPreferencesService userPreferencesService;
    private final RuntimeConfigService runtimeConfigService;

    public TierTool(
            UserPreferencesService userPreferencesService,
            RuntimeConfigService runtimeConfigService) {
        this.userPreferencesService = userPreferencesService;
        this.runtimeConfigService = runtimeConfigService;
    }

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
                        + "deep (PhD-level analysis), coding (programming), "
                        + "special1-special5 (explicit custom slots).")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "tier", Map.of(
                                        "type", "string",
                                        "enum", ModelTierCatalog.orderedExplicitTiers(),
                                        "description", "The model tier to switch to")),
                        "required", List.of("tier")))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        Object rawTier = parameters != null ? parameters.get("tier") : null;
        String tier = rawTier instanceof String tierValue ? ModelTierCatalog.normalizeTierId(tierValue) : null;

        if (tier == null || tier.isBlank()) {
            return CompletableFuture.completedFuture(ToolResult.failure("tier is required"));
        }

        if (!ModelTierCatalog.isExplicitSelectableTier(tier)) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(
                            "Invalid tier: " + tier + ". Valid tiers: "
                                    + ModelTierCatalog.explicitTierListForDisplay()));
        }

        AgentContext context = AgentContextHolder.get();
        if (context == null) {
            return CompletableFuture.completedFuture(ToolResult.failure("No agent context available"));
        }

        if (isTierForced(context)) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Tier is locked by user. Use /tier command to unlock."));
        }

        String previousTier = context.getModelTier();
        context.setModelTier(tier);

        log.info("[TierTool] Tier switched: {} -> {}", previousTier, tier);

        return CompletableFuture.completedFuture(
                ToolResult.success("Model tier switched to: " + tier));
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isTierToolEnabled();
    }

    private boolean isTierForced(AgentContext context) {
        if (context.getSession() != null && SessionModelSettingsSupport.hasModelSettings(context.getSession())) {
            return SessionModelSettingsSupport.readForce(context.getSession());
        }
        return userPreferencesService.getPreferences().isTierForce();
    }
}
