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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.port.outbound.HiveEventPublishPort;
import me.golemcore.bot.domain.model.hive.HiveEvidenceRef;
import me.golemcore.bot.domain.model.hive.HiveLifecycleSignalRequest;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class HiveLifecycleSignalTool implements ToolComponent {

    public static final String TOOL_NAME = ToolNames.HIVE_LIFECYCLE_SIGNAL;

    private static final List<String> SUPPORTED_SIGNAL_TYPES = List.of(
            "PROGRESS_REPORTED",
            "BLOCKER_RAISED",
            "BLOCKER_CLEARED",
            "REVIEW_REQUESTED",
            "WORK_COMPLETED",
            "WORK_FAILED");
    private static final Set<String> ALLOWED_SIGNAL_TYPES = Set.copyOf(SUPPORTED_SIGNAL_TYPES);

    private final HiveEventPublishPort hiveEventPublishPort;
    private final RuntimeConfigService runtimeConfigService;
    private final Clock clock;

    public HiveLifecycleSignalTool(
            HiveEventPublishPort hiveEventPublishPort,
            RuntimeConfigService runtimeConfigService,
            Clock clock) {
        this.hiveEventPublishPort = hiveEventPublishPort;
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("Emit a structured Hive card lifecycle signal for the active Hive card thread. "
                        + "Use this when the work is blocked, unblocked, ready for review, completed, or "
                        + "intentionally marked as failed. Plain text alone does not update Hive board state.")
                .inputSchema(Map.of(
                        "type", "object",
                        "required", List.of("signal_type", "summary"),
                        "properties", Map.of(
                                "signal_type", Map.of(
                                        "type", "string",
                                        "enum", SUPPORTED_SIGNAL_TYPES,
                                        "description", "Lifecycle signal type to emit."),
                                "summary", Map.of(
                                        "type", "string",
                                        "description", "Short operator-facing lifecycle summary."),
                                "details", Map.of(
                                        "type", "string",
                                        "description", "Optional longer explanation for operators."),
                                "blocker_code", Map.of(
                                        "type", "string",
                                        "description", "Optional machine-friendly blocker code for blocker signals."),
                                "evidence_refs", Map.of(
                                        "type", "array",
                                        "description", "Optional evidence references attached to the signal.",
                                        "items", Map.of(
                                                "type", "object",
                                                "required", List.of("kind", "ref"),
                                                "properties", Map.of(
                                                        "kind", Map.of(
                                                                "type", "string",
                                                                "description", "Evidence kind."),
                                                        "ref", Map.of(
                                                                "type", "string",
                                                                "description", "Evidence reference id or path.")))))))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isHiveSdlcLifecycleSignalEnabled();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        AgentContext context = AgentContextHolder.get();
        if (!isHiveContext(context)) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(ToolFailureKind.POLICY_DENIED,
                            "Hive lifecycle signals are only available in Hive sessions"));
        }

        String signalType = normalizeSignalType(parameters.get("signal_type"));
        String summary = normalizeString(parameters.get("summary"));
        String details = normalizeString(parameters.get("details"));
        String blockerCode = normalizeString(parameters.get("blocker_code"));
        if (!ALLOWED_SIGNAL_TYPES.contains(signalType)) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Unsupported Hive lifecycle signal type"));
        }
        if (summary == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Hive lifecycle signal summary is required"));
        }

        List<HiveEvidenceRef> evidenceRefs = parseEvidenceRefs(parameters.get("evidence_refs"));
        Map<String, Object> metadata = buildHiveMetadata(context);
        hiveEventPublishPort.publishLifecycleSignal(
                new HiveLifecycleSignalRequest(signalType, summary, details, blockerCode, evidenceRefs,
                        Instant.now(clock)),
                metadata);
        Map<String, Object> resultData = Map.of(
                "signalType", signalType,
                "summary", summary,
                "threadId", metadata.get(ContextAttributes.HIVE_THREAD_ID),
                "cardId", metadata.get(ContextAttributes.HIVE_CARD_ID));
        return CompletableFuture.completedFuture(HiveSdlcToolSupport.visibleSuccess(
                "Hive lifecycle signal emitted: " + signalType,
                resultData));
    }

    private boolean isHiveContext(AgentContext context) {
        return context != null
                && context.getSession() != null
                && "hive".equalsIgnoreCase(context.getSession().getChannelType())
                && hasText(context.getAttribute(ContextAttributes.HIVE_THREAD_ID))
                && hasText(context.getAttribute(ContextAttributes.HIVE_CARD_ID));
    }

    private Map<String, Object> buildHiveMetadata(AgentContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        copyMetadata(context, metadata, ContextAttributes.HIVE_THREAD_ID);
        copyMetadata(context, metadata, ContextAttributes.HIVE_CARD_ID);
        copyMetadata(context, metadata, ContextAttributes.HIVE_COMMAND_ID);
        copyMetadata(context, metadata, ContextAttributes.HIVE_RUN_ID);
        copyMetadata(context, metadata, ContextAttributes.HIVE_GOLEM_ID);
        return metadata;
    }

    private void copyMetadata(AgentContext context, Map<String, Object> metadata, String key) {
        String value = context.getAttribute(key);
        if (hasText(value)) {
            metadata.put(key, value);
        }
    }

    private List<HiveEvidenceRef> parseEvidenceRefs(Object rawValue) {
        if (!(rawValue instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<HiveEvidenceRef> evidenceRefs = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }
            String kind = normalizeString(rawMap.get("kind"));
            String ref = normalizeString(rawMap.get("ref"));
            if (kind == null || ref == null) {
                continue;
            }
            evidenceRefs.add(new HiveEvidenceRef(kind, ref));
        }
        return List.copyOf(evidenceRefs);
    }

    private String normalizeSignalType(Object value) {
        String normalized = normalizeString(value);
        return normalized != null ? normalized.toUpperCase(Locale.ROOT) : null;
    }

    private String normalizeString(Object value) {
        if (!(value instanceof String stringValue)) {
            return null;
        }
        String normalized = stringValue.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
