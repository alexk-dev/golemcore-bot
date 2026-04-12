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
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.model.hive.HiveCardSearchRequest;
import me.golemcore.bot.domain.model.hive.HiveCardSummary;
import me.golemcore.bot.domain.service.HiveSdlcService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HiveSearchCardsTool implements ToolComponent {

    private final HiveSdlcService hiveSdlcService;
    private final RuntimeConfigService runtimeConfigService;

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(ToolNames.HIVE_SEARCH_CARDS)
                .description("Search Hive cards using structured SDLC filters.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "service_id", Map.of("type", "string"),
                                "board_id", Map.of("type", "string"),
                                "kind", Map.of("type", "string"),
                                "parent_card_id", Map.of("type", "string"),
                                "epic_card_id", Map.of("type", "string"),
                                "review_of_card_id", Map.of("type", "string"),
                                "objective_id", Map.of("type", "string"),
                                "include_archived", Map.of("type", "boolean"))))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isHiveSdlcCardSearchEnabled();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        try {
            HiveSdlcToolSupport.requireHiveContext();
            HiveCardSearchRequest request = new HiveCardSearchRequest(
                    HiveSdlcToolSupport.stringParam(parameters, "service_id"),
                    HiveSdlcToolSupport.stringParam(parameters, "board_id"),
                    HiveSdlcToolSupport.stringParam(parameters, "kind"),
                    HiveSdlcToolSupport.stringParam(parameters, "parent_card_id"),
                    HiveSdlcToolSupport.stringParam(parameters, "epic_card_id"),
                    HiveSdlcToolSupport.stringParam(parameters, "review_of_card_id"),
                    HiveSdlcToolSupport.stringParam(parameters, "objective_id"),
                    Boolean.TRUE.equals(HiveSdlcToolSupport.booleanParam(parameters, "include_archived")));
            List<HiveCardSummary> cards = hiveSdlcService.searchCards(request);
            return CompletableFuture.completedFuture(ToolResult.success(
                    "Hive cards found: " + cards.size(),
                    Map.of("items", cards)));
        } catch (IllegalStateException exception) {
            return HiveSdlcToolSupport.failedFuture(exception.getMessage());
        } catch (RuntimeException exception) {
            return HiveSdlcToolSupport.executionFailedFuture(exception.getMessage());
        }
    }
}
