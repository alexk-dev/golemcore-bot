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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.HiveRuntimeConfigSupport;
import me.golemcore.bot.port.outbound.RuntimeConfigQueryPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HiveCurrentContextTool implements ToolComponent {

    private final RuntimeConfigQueryPort runtimeConfigQueryPort;

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(ToolNames.HIVE_GET_CURRENT_CONTEXT)
                .description(
                        "Return the active Hive SDLC context for this turn: card, thread, command, run, and golem ids.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of()))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return HiveRuntimeConfigSupport.isHiveSdlcCurrentContextEnabled(runtimeConfigQueryPort.getRuntimeConfig());
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        try {
            AgentContext context = HiveSdlcToolSupport.requireHiveContext();
            Map<String, Object> data = HiveSdlcToolSupport.currentContextData(context);
            return CompletableFuture.completedFuture(HiveSdlcToolSupport.visibleSuccess("Hive context loaded", data));
        } catch (IllegalStateException exception) {
            return HiveSdlcToolSupport.failedFuture(exception.getMessage());
        }
    }
}
