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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.outbound.hive.HiveEventBatchPublisher;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.RuntimeEvent;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class HiveRuntimeEventDispatchSystem implements AgentSystem {

    private final HiveEventBatchPublisher hiveEventBatchPublisher;

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
        if (context == null || context.getSession() == null || context.getSession().getChannelType() == null
                || !"hive".equalsIgnoreCase(context.getSession().getChannelType())) {
            return context;
        }
        List<RuntimeEvent> runtimeEvents = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        if (runtimeEvents == null || runtimeEvents.isEmpty()) {
            return context;
        }
        try {
            hiveEventBatchPublisher.publishRuntimeEvents(runtimeEvents, buildMetadata(context));
        } catch (RuntimeException exception) {
            log.warn("[Hive] Failed to dispatch runtime event batch: {}", exception.getMessage());
        }
        return context;
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
}
