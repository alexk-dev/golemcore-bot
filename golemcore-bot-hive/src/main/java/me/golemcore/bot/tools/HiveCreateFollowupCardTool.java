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
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.model.hive.HiveCardDetail;
import me.golemcore.bot.domain.model.hive.HiveCreateCardRequest;
import me.golemcore.bot.domain.service.HiveRuntimeConfigSupport;
import me.golemcore.bot.domain.service.HiveSdlcService;
import me.golemcore.bot.port.outbound.RuntimeConfigQueryPort;
import org.springframework.stereotype.Component;

@Component
public class HiveCreateFollowupCardTool implements ToolComponent {

    private final HiveSdlcService hiveSdlcService;
    private final RuntimeConfigQueryPort runtimeConfigQueryPort;

    public HiveCreateFollowupCardTool(
            HiveSdlcService hiveSdlcService,
            RuntimeConfigQueryPort runtimeConfigQueryPort) {
        this.hiveSdlcService = hiveSdlcService;
        this.runtimeConfigQueryPort = runtimeConfigQueryPort;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(ToolNames.HIVE_CREATE_FOLLOWUP_CARD)
                .description(
                        "Create a Hive follow-up, subtask, or review card. Defaults can be inherited from the active card when inherit_current_card is true.")
                .inputSchema(Map.of(
                        "type", "object",
                        "required", List.of("title", "prompt"),
                        "properties", Map.ofEntries(
                                Map.entry("title", Map.of("type", "string")),
                                Map.entry("description", Map.of("type", "string")),
                                Map.entry("prompt", Map.of("type", "string")),
                                Map.entry("service_id", Map.of("type", "string")),
                                Map.entry("board_id", Map.of("type", "string")),
                                Map.entry("column_id", Map.of("type", "string")),
                                Map.entry("kind", Map.of("type", "string")),
                                Map.entry("parent_card_id", Map.of("type", "string")),
                                Map.entry("epic_card_id", Map.of("type", "string")),
                                Map.entry("review_of_card_id", Map.of("type", "string")),
                                Map.entry("depends_on_card_ids", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"))),
                                Map.entry("team_id", Map.of("type", "string")),
                                Map.entry("objective_id", Map.of("type", "string")),
                                Map.entry("assignee_golem_id", Map.of("type", "string")),
                                Map.entry("assignment_policy", Map.of("type", "string")),
                                Map.entry("auto_assign", Map.of("type", "boolean")),
                                Map.entry("inherit_current_card", Map.of(
                                        "type", "boolean",
                                        "description",
                                        "When true, use the active card as parent_card_id if no parent is specified.")))))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return HiveRuntimeConfigSupport.isHiveSdlcFollowupCardCreateEnabled(runtimeConfigQueryPort.getRuntimeConfig());
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        try {
            AgentContext context = HiveSdlcToolSupport.requireHiveContext();
            String title = HiveSdlcToolSupport.stringParam(parameters, "title");
            String prompt = HiveSdlcToolSupport.stringParam(parameters, "prompt");
            if (title == null) {
                return HiveSdlcToolSupport.executionFailedFuture("Hive follow-up card title is required");
            }
            if (prompt == null) {
                return HiveSdlcToolSupport.executionFailedFuture("Hive follow-up card prompt is required");
            }
            HiveCardDetail card = hiveSdlcService.createCard(buildRequest(context, parameters, title, prompt));
            return CompletableFuture
                    .completedFuture(HiveSdlcToolSupport.visibleSuccess("Hive follow-up card created: " + card.id(),
                            card));
        } catch (IllegalStateException exception) {
            return HiveSdlcToolSupport.failedFuture(exception.getMessage());
        } catch (RuntimeException exception) {
            return HiveSdlcToolSupport.executionFailedFuture(exception.getMessage());
        }
    }

    private HiveCreateCardRequest buildRequest(
            AgentContext context,
            Map<String, Object> parameters,
            String title,
            String prompt) {
        boolean inheritCurrentCard = HiveSdlcToolSupport.booleanParam(parameters, "inherit_current_card");
        String currentCardId = HiveSdlcToolSupport.contextAttribute(context, ContextAttributes.HIVE_CARD_ID);
        String parentCardId = HiveSdlcToolSupport.stringParam(parameters, "parent_card_id");
        if (parentCardId == null && inheritCurrentCard) {
            parentCardId = currentCardId;
        }
        return new HiveCreateCardRequest(
                HiveSdlcToolSupport.stringParam(parameters, "service_id"),
                HiveSdlcToolSupport.stringParam(parameters, "board_id"),
                title,
                HiveSdlcToolSupport.stringParam(parameters, "description"),
                prompt,
                HiveSdlcToolSupport.stringParam(parameters, "column_id"),
                HiveSdlcToolSupport.stringParam(parameters, "kind"),
                parentCardId,
                HiveSdlcToolSupport.stringParam(parameters, "epic_card_id"),
                HiveSdlcToolSupport.stringParam(parameters, "review_of_card_id"),
                HiveSdlcToolSupport.stringListParam(parameters, "depends_on_card_ids"),
                HiveSdlcToolSupport.stringParam(parameters, "team_id"),
                HiveSdlcToolSupport.stringParam(parameters, "objective_id"),
                HiveSdlcToolSupport.stringParam(parameters, "assignee_golem_id"),
                HiveSdlcToolSupport.stringParam(parameters, "assignment_policy"),
                HiveSdlcToolSupport.booleanParam(parameters, "auto_assign"));
    }
}
