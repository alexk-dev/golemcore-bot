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
import me.golemcore.bot.domain.model.hive.HiveCardDetail;
import me.golemcore.bot.domain.model.hive.HiveRequestReviewRequest;
import me.golemcore.bot.domain.service.HiveSdlcService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HiveRequestReviewTool implements ToolComponent {

    private final HiveSdlcService hiveSdlcService;
    private final RuntimeConfigService runtimeConfigService;

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(ToolNames.HIVE_REQUEST_REVIEW)
                .description(
                        "Request Hive review for a card, optionally specifying reviewer golems, reviewer team, and required review count.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "card_id", Map.of(
                                        "type", "string",
                                        "description", "Optional Hive card id. Defaults to the current card."),
                                "reviewer_golem_ids", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string")),
                                "reviewer_team_id", Map.of("type", "string"),
                                "required_review_count", Map.of("type", "integer"))))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isHiveSdlcReviewRequestEnabled();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        try {
            AgentContext context = HiveSdlcToolSupport.requireHiveContext();
            String cardId = HiveSdlcToolSupport.resolveCardId(context, parameters);
            if (cardId == null) {
                return HiveSdlcToolSupport.executionFailedFuture("Hive card_id is required");
            }
            HiveRequestReviewRequest request = new HiveRequestReviewRequest(
                    HiveSdlcToolSupport.stringListParam(parameters, "reviewer_golem_ids"),
                    HiveSdlcToolSupport.stringParam(parameters, "reviewer_team_id"),
                    HiveSdlcToolSupport.integerParam(parameters, "required_review_count"));
            HiveCardDetail card = hiveSdlcService.requestReview(cardId, request);
            return CompletableFuture.completedFuture(ToolResult.success("Hive review requested for card: " + card.id(),
                    card));
        } catch (IllegalStateException exception) {
            return HiveSdlcToolSupport.failedFuture(exception.getMessage());
        } catch (RuntimeException exception) {
            return HiveSdlcToolSupport.executionFailedFuture(exception.getMessage());
        }
    }
}
