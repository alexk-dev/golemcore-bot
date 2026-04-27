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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillTransitionRequest;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.DynamicSkillFactory;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Tool for searching and activating MCP servers from the catalog.
 *
 * <p>
 * The LLM calls this tool when it determines it needs an external tool
 * capability that is not currently available. The tool searches the MCP
 * catalog, materializes a matching catalog entry into a skill, registers it,
 * and initiates a skill transition to activate it.
 *
 * <p>
 * Two modes of operation:
 * <ul>
 * <li>{@code action=search}: Lists catalog entries matching a query</li>
 * <li>{@code action=activate}: Materializes and transitions to a specific
 * catalog entry</li>
 * </ul>
 *
 * <p>
 * Uses {@link AgentContextHolder} to modify the current {@link AgentContext}.
 * Must NOT use {@code CompletableFuture.supplyAsync()} as ThreadLocal is not
 * propagated.
 */
@Component
@Slf4j
public class DiscoverMcpServerTool implements ToolComponent {

    private final RuntimeConfigService runtimeConfigService;
    private final DynamicSkillFactory dynamicSkillFactory;
    private final SkillComponent skillComponent;

    public DiscoverMcpServerTool(
            RuntimeConfigService runtimeConfigService,
            DynamicSkillFactory dynamicSkillFactory,
            SkillComponent skillComponent) {
        this.runtimeConfigService = runtimeConfigService;
        this.dynamicSkillFactory = dynamicSkillFactory;
        this.skillComponent = skillComponent;
    }

    @Override
    public String getToolName() {
        return "discover_mcp_server";
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("discover_mcp_server")
                .description("Search for and activate an MCP tool server from the catalog. "
                        + "Use action='search' to find available servers by keyword, "
                        + "or action='activate' with a server name to start it and gain access to its tools.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "action", Map.of(
                                        "type", "string",
                                        "enum", List.of("search", "activate"),
                                        "description",
                                        "search = list matching servers, activate = start a server and transition to it"),
                                "query", Map.of(
                                        "type", "string",
                                        "description",
                                        "Search keyword to match against server names and descriptions (for action=search)"),
                                "server_name", Map.of(
                                        "type", "string",
                                        "description", "Exact catalog entry name to activate (for action=activate)")),
                        "required", List.of("action")))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        String action = (String) parameters.get("action");

        if (action == null || action.isBlank()) {
            return CompletableFuture.completedFuture(ToolResult.failure("action is required"));
        }

        return switch (action) {
        case "search" -> handleSearch(parameters);
        case "activate" -> handleActivate(parameters);
        default -> CompletableFuture.completedFuture(
                ToolResult.failure("Invalid action: " + action + ". Use 'search' or 'activate'."));
        };
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isMcpEnabled();
    }

    private CompletableFuture<ToolResult> handleSearch(Map<String, Object> parameters) {
        String query = (String) parameters.get("query");
        List<RuntimeConfig.McpCatalogEntry> catalog = runtimeConfigService.getMcpCatalog();

        if (catalog.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ToolResult.success("MCP catalog is empty. No servers are registered."));
        }

        List<RuntimeConfig.McpCatalogEntry> matches = catalog.stream()
                .filter(entry -> entry.getEnabled() == null || entry.getEnabled())
                .filter(entry -> matchesQuery(entry, query))
                .toList();

        if (matches.isEmpty()) {
            String allNames = catalog.stream()
                    .filter(entry -> entry.getEnabled() == null || entry.getEnabled())
                    .map(RuntimeConfig.McpCatalogEntry::getName)
                    .collect(Collectors.joining(", "));
            return CompletableFuture.completedFuture(
                    ToolResult.success("No servers match query '" + query + "'. Available: " + allNames));
        }

        String result = matches.stream()
                .map(entry -> formatEntry(entry))
                .collect(Collectors.joining("\n"));

        return CompletableFuture.completedFuture(
                ToolResult.success("Found " + matches.size() + " MCP server(s):\n" + result));
    }

    private CompletableFuture<ToolResult> handleActivate(Map<String, Object> parameters) {
        String serverName = (String) parameters.get("server_name");
        if (serverName == null || serverName.isBlank()) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("server_name is required for action=activate"));
        }

        String normalizedName = serverName.toLowerCase(Locale.ROOT).trim();

        // Check if already registered as a skill (manual or previously materialized)
        String skillName = dynamicSkillFactory.toSkillName(normalizedName);
        Optional<Skill> existingSkill = skillComponent.findByName(skillName);

        if (existingSkill.isPresent()) {
            return activateSkill(existingSkill.get());
        }

        // Also check if a manual skill covers this MCP server
        Optional<Skill> manualSkill = findManualSkillForServer(normalizedName);
        if (manualSkill.isPresent()) {
            return activateSkill(manualSkill.get());
        }

        // Find in catalog and materialize
        List<RuntimeConfig.McpCatalogEntry> catalog = runtimeConfigService.getMcpCatalog();
        Optional<RuntimeConfig.McpCatalogEntry> entry = catalog.stream()
                .filter(e -> normalizedName.equals(e.getName()))
                .filter(e -> e.getEnabled() == null || e.getEnabled())
                .findFirst();

        if (entry.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("MCP server '" + normalizedName + "' not found in catalog or is disabled."));
        }

        // Materialize and register
        Skill newSkill = dynamicSkillFactory.materialize(entry.get());
        skillComponent.registerDynamicSkill(newSkill);

        log.info("[DiscoverMcp] Materialized catalog entry '{}' as skill '{}'", normalizedName, newSkill.getName());

        return activateSkill(newSkill);
    }

    private CompletableFuture<ToolResult> activateSkill(Skill skill) {
        AgentContext context = AgentContextHolder.get();
        if (context == null) {
            return CompletableFuture.completedFuture(ToolResult.failure("No agent context available"));
        }

        context.setSkillTransitionRequest(SkillTransitionRequest.explicit(skill.getName()));

        log.info("[DiscoverMcp] Activating MCP skill '{}'", skill.getName());

        return CompletableFuture.completedFuture(ToolResult.success(
                "Activating MCP server: " + skill.getName()
                        + ". The server's tools will be available in the next turn."));
    }

    private Optional<Skill> findManualSkillForServer(String serverName) {
        return skillComponent.getAvailableSkills().stream()
                .filter(Skill::hasMcp)
                .filter(skill -> !dynamicSkillFactory.isCatalogSkill(skill.getName()))
                .filter(skill -> skill.getMcpConfig().getCommand().contains(serverName))
                .findFirst();
    }

    private boolean matchesQuery(RuntimeConfig.McpCatalogEntry entry, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        String name = entry.getName() != null ? entry.getName().toLowerCase(Locale.ROOT) : "";
        String description = entry.getDescription() != null ? entry.getDescription().toLowerCase(Locale.ROOT) : "";
        String command = entry.getCommand() != null ? entry.getCommand().toLowerCase(Locale.ROOT) : "";
        return name.contains(lowerQuery) || description.contains(lowerQuery) || command.contains(lowerQuery);
    }

    private String formatEntry(RuntimeConfig.McpCatalogEntry entry) {
        String desc = entry.getDescription() != null ? " — " + entry.getDescription() : "";
        return "- **" + entry.getName() + "**" + desc + " (command: `" + entry.getCommand() + "`)";
    }
}
