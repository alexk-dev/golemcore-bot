package me.golemcore.bot.domain.system;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.service.HiveSessionStateStore;
import me.golemcore.bot.port.outbound.HiveEventPublishPort;
import me.golemcore.bot.port.outbound.SelfEvolvingTacticSearchStatusPort;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class HiveRuntimeEventDispatchSystem implements AgentSystem {

    private final HiveEventPublishPort hiveEventPublishPort;
    private final HiveSessionStateStore hiveSessionStateStore;
    private final SelfEvolvingTacticSearchStatusPort tacticSearchStatusPort;

    public HiveRuntimeEventDispatchSystem(
            HiveEventPublishPort hiveEventPublishPort,
            HiveSessionStateStore hiveSessionStateStore,
            SelfEvolvingTacticSearchStatusPort tacticSearchStatusPort) {
        this.hiveEventPublishPort = hiveEventPublishPort;
        this.hiveSessionStateStore = hiveSessionStateStore;
        this.tacticSearchStatusPort = tacticSearchStatusPort;
    }

    @Override
    public String getName() {
        return "HiveRuntimeEventDispatchSystem";
    }

    @Override
    public int getOrder() {
        return 61;
    }

    @Override
    public AgentContext process(AgentContext context) {
        publishTacticSearch(context);
        if (context == null || context.getSession() == null || context.getSession().getChannelType() == null
                || !"hive".equalsIgnoreCase(context.getSession().getChannelType())) {
            return context;
        }
        List<RuntimeEvent> runtimeEvents = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        if (runtimeEvents == null || runtimeEvents.isEmpty()) {
            return context;
        }
        try {
            hiveEventPublishPort.publishRuntimeEvents(runtimeEvents, buildMetadata(context));
        } catch (RuntimeException exception) {
            log.warn("[Hive] Failed to dispatch runtime event batch: {}", exception.getMessage());
        }
        return context;
    }

    private void publishTacticSearch(AgentContext context) {
        if (context == null) {
            return;
        }
        if (!isHiveSessionAvailable()) {
            return;
        }
        Object queryValue = context.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY);
        if (!(queryValue instanceof TacticSearchQuery tacticSearchQuery)) {
            return;
        }
        String query = resolveQuery(tacticSearchQuery);
        if (query == null) {
            return;
        }
        List<TacticSearchResult> results = extractTacticResults(context);
        try {
            hiveEventPublishPort.publishSelfEvolvingTacticSearchProjection(
                    query,
                    buildTacticSearchStatus(),
                    results);
        } catch (RuntimeException exception) {
            log.warn("[Hive] Failed to publish tactic search projection: {}", exception.getMessage());
        }
    }

    private boolean isHiveSessionAvailable() {
        if (hiveSessionStateStore == null) {
            return true;
        }
        return hiveSessionStateStore.load()
                .map(this::isCompleteHiveSession)
                .orElse(false);
    }

    private boolean isCompleteHiveSession(HiveSessionState sessionState) {
        return sessionState != null
                && !isBlank(sessionState.getServerUrl())
                && !isBlank(sessionState.getGolemId())
                && !isBlank(sessionState.getAccessToken());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> buildMetadata(AgentContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        copy(context, metadata, ContextAttributes.HIVE_CARD_ID);
        copy(context, metadata, ContextAttributes.HIVE_THREAD_ID);
        copy(context, metadata, ContextAttributes.HIVE_COMMAND_ID);
        copy(context, metadata, ContextAttributes.HIVE_RUN_ID);
        copy(context, metadata, ContextAttributes.HIVE_GOLEM_ID);
        return metadata;
    }

    private void copy(AgentContext context, Map<String, Object> metadata, String key) {
        Object value = context.getAttribute(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            metadata.put(key, stringValue);
        }
    }

    private List<TacticSearchResult> extractTacticResults(AgentContext context) {
        Object resultsValue = context.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_RESULTS);
        if (!(resultsValue instanceof List<?> rawResults) || rawResults.isEmpty()) {
            return List.of();
        }
        return rawResults.stream()
                .filter(TacticSearchResult.class::isInstance)
                .map(TacticSearchResult.class::cast)
                .toList();
    }

    private String resolveQuery(TacticSearchQuery tacticSearchQuery) {
        if (tacticSearchQuery.getRawQuery() != null && !tacticSearchQuery.getRawQuery().isBlank()) {
            return tacticSearchQuery.getRawQuery().trim();
        }
        if (tacticSearchQuery.getQueryViews() != null && !tacticSearchQuery.getQueryViews().isEmpty()) {
            return String.join(" ", tacticSearchQuery.getQueryViews()).trim();
        }
        return null;
    }

    private TacticSearchStatus buildTacticSearchStatus() {
        if (tacticSearchStatusPort != null) {
            return tacticSearchStatusPort.getCurrentStatus();
        }
        return TacticSearchStatus.builder()
                .mode("bm25")
                .degraded(false)
                .build();
    }
}
