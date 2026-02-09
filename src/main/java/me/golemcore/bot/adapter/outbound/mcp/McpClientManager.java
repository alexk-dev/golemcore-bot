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

package me.golemcore.bot.adapter.outbound.mcp;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.McpPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages MCP client lifecycles — pool of McpClient instances keyed by skill
 * name.
 *
 * <p>
 * This manager provides:
 * <ul>
 * <li>Lazy startup: MCP servers are only started when a skill is activated
 * <li>Idle timeout: Servers are stopped after N minutes of inactivity
 * <li>Tool name tracking: Maps skill names to MCP tool names for cleanup
 * <li>@PreDestroy shutdown: Stops all clients on application shutdown
 * </ul>
 *
 * <p>
 * Integration flow:
 * <ol>
 * <li>{@link me.golemcore.bot.domain.system.ContextBuildingSystem} calls
 * {@link #getOrStartClient(Skill)} when activeSkill has MCP config
 * <li>Manager creates {@link McpClient}, starts server, fetches tools
 * <li>Tools wrapped as {@link McpToolAdapter} and registered in
 * ToolExecutionSystem
 * <li>Manager cleans up idle clients every 60 seconds
 * </ol>
 *
 * <p>
 * Configuration:
 * <ul>
 * <li>{@code bot.mcp.enabled} - Enable/disable MCP feature
 * <li>{@code bot.mcp.default-startup-timeout} - Default startup timeout
 * (seconds)
 * <li>{@code bot.mcp.default-idle-timeout} - Default idle timeout (minutes)
 * </ul>
 *
 * @see McpClient
 * @see McpToolAdapter
 * @see me.golemcore.bot.domain.system.ContextBuildingSystem
 */
@Component
@Slf4j
public class McpClientManager implements McpPort {

    private final BotProperties properties;
    private final ObjectMapper objectMapper;

    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<String>> skillToolNames = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcp-idle-check");
        t.setDaemon(true);
        return t;
    });

    public McpClientManager(BotProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        // Schedule idle check every 60 seconds
        scheduler.scheduleAtFixedRate(this::checkIdleClients, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public List<ToolDefinition> getOrStartClient(Skill skill) {
        if (!properties.getMcp().isEnabled()) {
            return List.of();
        }

        if (!skill.hasMcp()) {
            return List.of();
        }

        McpClient existing = clients.get(skill.getName());
        if (existing != null && existing.isRunning()) {
            return existing.getCachedTools();
        }

        // Start a new client
        synchronized (this) {
            // Double-check after lock
            existing = clients.get(skill.getName());
            if (existing != null && existing.isRunning()) {
                return existing.getCachedTools();
            }

            // Close stale client if it exists but isn't running
            if (existing != null) {
                existing.close();
                clients.remove(skill.getName());
            }

            McpConfig config = applyDefaults(skill.getMcpConfig());
            McpClient client = new McpClient(skill.getName(), config, objectMapper);

            try {
                List<ToolDefinition> tools = client.start();
                clients.put(skill.getName(), client);
                skillToolNames.put(skill.getName(), tools.stream().map(ToolDefinition::getName).toList());
                log.info("[McpManager] Started client for skill '{}', {} tools", skill.getName(), tools.size());
                return tools;
            } catch (Exception e) {
                log.error("[McpManager] Failed to start MCP client for skill '{}': {}", skill.getName(), e.getMessage(),
                        e);
                client.close();
                return List.of();
            }
        }
    }

    /**
     * Get a running client by skill name.
     */
    public Optional<McpClient> getClient(String skillName) {
        McpClient client = clients.get(skillName);
        if (client != null && client.isRunning()) {
            return Optional.of(client);
        }
        return Optional.empty();
    }

    @Override
    public ToolComponent createToolAdapter(String skillName, ToolDefinition definition) {
        return new McpToolAdapter(skillName, definition, this);
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public List<String> stopClient(String skillName) {
        McpClient client = clients.remove(skillName);
        List<String> toolNames = skillToolNames.remove(skillName);

        if (client != null) {
            client.close();
            log.info("[McpManager] Stopped client for skill '{}'", skillName);
        }

        return toolNames != null ? toolNames : List.of();
    }

    @Override
    public List<String> getToolNames(String skillName) {
        return skillToolNames.getOrDefault(skillName, List.of());
    }

    @PreDestroy
    public void shutdown() {
        log.info("[McpManager] Shutting down all MCP clients");
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("[McpManager] Error closing client '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        clients.clear();
        skillToolNames.clear();
    }

    @SuppressWarnings("PMD.CloseResource")
    private void checkIdleClients() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            McpClient client = entry.getValue();
            if (!client.isRunning()) {
                clients.remove(entry.getKey());
                skillToolNames.remove(entry.getKey());
                continue;
            }

            long idleMs = now - client.getLastActivityTimestamp();
            // Use the per-client config from McpConfig (stored during start)
            long idleTimeoutMs = TimeUnit.MINUTES.toMillis(properties.getMcp().getDefaultIdleTimeout());
            if (idleMs > idleTimeoutMs) {
                log.info("[McpManager] Stopping idle client '{}' (idle for {}s)", entry.getKey(), idleMs / 1000);
                stopClient(entry.getKey());
            }
        }
    }

    private McpConfig applyDefaults(McpConfig config) {
        BotProperties.McpClientProperties defaults = properties.getMcp();
        return McpConfig.builder()
                .command(config.getCommand())
                .env(config.getEnv())
                .startupTimeoutSeconds(config.getStartupTimeoutSeconds() > 0
                        ? config.getStartupTimeoutSeconds()
                        : defaults.getDefaultStartupTimeout())
                .idleTimeoutMinutes(config.getIdleTimeoutMinutes() > 0
                        ? config.getIdleTimeoutMinutes()
                        : defaults.getDefaultIdleTimeout())
                .build();
    }
}
