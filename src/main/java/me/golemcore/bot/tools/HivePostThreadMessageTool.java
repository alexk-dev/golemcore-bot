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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.model.hive.HiveThreadMessage;
import me.golemcore.bot.domain.service.HiveSdlcService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HivePostThreadMessageTool implements ToolComponent {

    private final HiveSdlcService hiveSdlcService;
    private final RuntimeConfigService runtimeConfigService;

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(ToolNames.HIVE_POST_THREAD_MESSAGE)
                .description(
                        "Post an SDLC note into a Hive card thread. Use this for operator-facing progress, evidence, or handoff notes.")
                .inputSchema(Map.of(
                        "type", "object",
                        "required", List.of("body"),
                        "properties", Map.of(
                                "thread_id", Map.of(
                                        "type", "string",
                                        "description", "Optional Hive thread id. Defaults to the current thread."),
                                "body", Map.of(
                                        "type", "string",
                                        "description", "Thread message body to post."))))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isHiveSdlcThreadMessageEnabled();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        try {
            AgentContext context = HiveSdlcToolSupport.requireHiveContext();
            String threadId = HiveSdlcToolSupport.resolveThreadId(context, parameters);
            String body = HiveSdlcToolSupport.stringParam(parameters, "body");
            if (threadId == null) {
                return HiveSdlcToolSupport.executionFailedFuture("Hive thread_id is required");
            }
            if (body == null) {
                return HiveSdlcToolSupport.executionFailedFuture("Hive thread message body is required");
            }
            HiveThreadMessage message = hiveSdlcService.postThreadMessage(threadId, body);
            return CompletableFuture.completedFuture(ToolResult.success(
                    "Hive thread message posted: " + message.id(),
                    message));
        } catch (IllegalStateException exception) {
            return HiveSdlcToolSupport.failedFuture(exception.getMessage());
        } catch (RuntimeException exception) {
            return HiveSdlcToolSupport.executionFailedFuture(exception.getMessage());
        }
    }
}
