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
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.model.hive.HiveCardDetail;
import me.golemcore.bot.domain.model.hive.HiveRequestReviewRequest;
import me.golemcore.bot.domain.hive.HiveRuntimeConfigSupport;
import me.golemcore.bot.domain.hive.HiveSdlcService;
import me.golemcore.bot.port.outbound.RuntimeConfigQueryPort;
import org.springframework.stereotype.Component;

@Component
public class HiveRequestReviewTool implements ToolComponent {

    private final HiveSdlcService hiveSdlcService;
    private final RuntimeConfigQueryPort runtimeConfigQueryPort;

    public HiveRequestReviewTool(
            HiveSdlcService hiveSdlcService,
            RuntimeConfigQueryPort runtimeConfigQueryPort) {
        this.hiveSdlcService = hiveSdlcService;
        this.runtimeConfigQueryPort = runtimeConfigQueryPort;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(ToolNames.HIVE_REQUEST_REVIEW)
                .description(
                        "Request Hive review for a card. If reviewer inputs are omitted, existing reviewer settings from the Hive card are reused.")
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
        return HiveRuntimeConfigSupport.isHiveSdlcReviewRequestEnabled(runtimeConfigQueryPort.getRuntimeConfig());
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        try {
            AgentContext context = HiveSdlcToolSupport.requireHiveContext();
            String cardId = HiveSdlcToolSupport.resolveCardId(context, parameters);
            if (cardId == null) {
                return HiveSdlcToolSupport.executionFailedFuture("Hive card_id is required");
            }
            HiveRequestReviewRequest request = buildReviewRequest(parameters, cardId);
            HiveCardDetail card = hiveSdlcService.requestReview(cardId, request);
            return CompletableFuture
                    .completedFuture(HiveSdlcToolSupport.visibleSuccess("Hive review requested for card: " + card.id(),
                            card));
        } catch (IllegalStateException exception) {
            return HiveSdlcToolSupport.failedFuture(exception.getMessage());
        } catch (RuntimeException exception) {
            return HiveSdlcToolSupport.executionFailedFuture(exception.getMessage());
        }
    }

    private HiveRequestReviewRequest buildReviewRequest(Map<String, Object> parameters, String cardId) {
        List<String> reviewerGolemIds = HiveSdlcToolSupport.stringListParam(parameters, "reviewer_golem_ids");
        String reviewerTeamId = HiveSdlcToolSupport.stringParam(parameters, "reviewer_team_id");
        Integer requiredReviewCount = HiveSdlcToolSupport.integerParam(parameters, "required_review_count");
        if (!reviewerGolemIds.isEmpty() || reviewerTeamId != null) {
            return new HiveRequestReviewRequest(reviewerGolemIds, reviewerTeamId, requiredReviewCount);
        }

        HiveCardDetail currentCard = hiveSdlcService.getCard(cardId);
        List<String> cardReviewerGolemIds = currentCard.reviewerGolemIds();
        String cardReviewerTeamId = currentCard.reviewerTeamId();
        if ((cardReviewerGolemIds == null || cardReviewerGolemIds.isEmpty()) && cardReviewerTeamId == null) {
            throw new IllegalArgumentException(
                    "Hive review request requires reviewer_golem_ids or reviewer_team_id because the card has no reviewer settings");
        }
        Integer effectiveReviewCount = requiredReviewCount != null ? requiredReviewCount
                : currentCard.requiredReviewCount();
        return new HiveRequestReviewRequest(
                cardReviewerGolemIds != null ? cardReviewerGolemIds : List.of(),
                cardReviewerTeamId,
                effectiveReviewCount);
    }
}
