package me.golemcore.bot.tools;

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

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.DelayedActionDeliveryMode;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.StringValueSupport;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool for creating and managing delayed session-scoped actions.
 */
@Component
public class ScheduleSessionActionTool implements ToolComponent {

    public static final String TOOL_NAME = ToolNames.SCHEDULE_SESSION_ACTION;

    private final DelayedSessionActionService delayedActionService;
    private final DelayedActionPolicyService delayedActionPolicyService;
    private final RuntimeConfigService runtimeConfigService;
    private final Clock clock;

    public ScheduleSessionActionTool(DelayedSessionActionService delayedActionService,
            DelayedActionPolicyService delayedActionPolicyService,
            RuntimeConfigService runtimeConfigService,
            Clock clock) {
        this.delayedActionService = delayedActionService;
        this.delayedActionPolicyService = delayedActionPolicyService;
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("""
                        Create or manage delayed actions bound to the current session.
                        Use this when the user asks for a reminder later, wants the agent to do something later,
                        or asks to inspect/cancel existing delayed actions.
                        """)
                .inputSchema(Map.of(
                        "type", "object",
                        "required", List.of("operation"),
                        "properties", Map.ofEntries(
                                Map.entry("operation", Map.of(
                                        "type", "string",
                                        "enum", List.of("create", "list", "cancel", "run_now"),
                                        "description",
                                        "create schedules a new action. list shows delayed actions for the current session. cancel cancels an action by id. run_now makes a scheduled action due immediately.")),
                                Map.entry("action_kind", Map.of(
                                        "type", "string",
                                        "enum", List.of("remind_later", "run_later", "notify_job_ready"),
                                        "description", "Kind of delayed action to create.")),
                                Map.entry("delay_seconds", Map.of(
                                        "type", "integer",
                                        "minimum", 1,
                                        "description", "Relative delay in seconds.")),
                                Map.entry("run_at", Map.of(
                                        "type", "string",
                                        "description",
                                        "Absolute ISO-8601 timestamp with timezone, for example 2026-03-19T18:35:00Z.")),
                                Map.entry("instruction", Map.of(
                                        "type", "string",
                                        "description", "Instruction snapshot for run_later.")),
                                Map.entry("message", Map.of(
                                        "type", "string",
                                        "description", "Reminder or notification text.")),
                                Map.entry("job_id", Map.of(
                                        "type", "string",
                                        "description", "Optional background job id for notify_job_ready.")),
                                Map.entry("artifact_path", Map.of(
                                        "type", "string",
                                        "description", "Optional tool artifact path for direct file delivery.")),
                                Map.entry("artifact_name", Map.of(
                                        "type", "string",
                                        "description", "Optional filename override for file delivery.")),
                                Map.entry("original_summary", Map.of(
                                        "type", "string",
                                        "description", "Optional short summary of the original request.")),
                                Map.entry("cancel_on_user_activity", Map.of(
                                        "type", "boolean",
                                        "description",
                                        "Cancel the action if the user becomes active before it fires.")),
                                Map.entry("max_attempts", Map.of(
                                        "type", "integer",
                                        "minimum", 1,
                                        "description", "Optional retry budget.")),
                                Map.entry("dedupe_key", Map.of(
                                        "type", "string",
                                        "description", "Optional idempotency key.")),
                                Map.entry("action_id", Map.of(
                                        "type", "string",
                                        "description", "Delayed action id for cancel/run_now.")))))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.completedFuture(executeInternal(parameters));
    }

    @Override
    public boolean isEnabled() {
        if (!runtimeConfigService.isDelayedActionsEnabled()) {
            return false;
        }
        AgentContext context = AgentContextHolder.get();
        if (context == null || context.getSession() == null) {
            return true;
        }
        return delayedActionPolicyService.canScheduleActions(context.getSession().getChannelType());
    }

    private ToolResult executeInternal(Map<String, Object> parameters) {
        AgentContext context = AgentContextHolder.get();
        if (context == null || context.getSession() == null) {
            return ToolResult.failure(ToolFailureKind.POLICY_DENIED, "No active session context");
        }

        String channelType = context.getSession().getChannelType();
        String conversationKey = SessionIdentitySupport.resolveConversationKey(context.getSession());
        String transportChatId = SessionIdentitySupport.resolveTransportChatId(context.getSession());
        if (!delayedActionPolicyService.canScheduleActions(channelType)) {
            return ToolResult.failure(ToolFailureKind.POLICY_DENIED,
                    "Delayed actions are unavailable for this channel");
        }
        String operation = stringParam(parameters, "operation");
        if (StringValueSupport.isBlank(operation)) {
            return ToolResult.failure("Missing required parameter: operation");
        }

        return switch (operation.trim().toLowerCase(java.util.Locale.ROOT)) {
        case "create" -> createAction(parameters, channelType, conversationKey, transportChatId);
        case "list" -> listActions(channelType, conversationKey);
        case "cancel" -> cancelAction(parameters, channelType, conversationKey);
        case "run_now" -> runNow(parameters, channelType, conversationKey);
        default -> ToolResult.failure("Unknown operation: " + operation);
        };
    }

    private ToolResult createAction(Map<String, Object> parameters, String channelType,
            String conversationKey, String transportChatId) {
        String actionKind = stringParam(parameters, "action_kind");
        if (StringValueSupport.isBlank(actionKind)) {
            return ToolResult.failure("Missing required parameter: action_kind");
        }
        DelayedActionKind kind = switch (actionKind.trim().toLowerCase(java.util.Locale.ROOT)) {
        case "remind_later" -> DelayedActionKind.REMIND_LATER;
        case "run_later" -> DelayedActionKind.RUN_LATER;
        case "notify_job_ready" -> DelayedActionKind.NOTIFY_JOB_READY;
        default -> null;
        };
        if (kind == null) {
            return ToolResult.failure("Unsupported action_kind: " + actionKind);
        }
        if (kind == DelayedActionKind.RUN_LATER
                && !delayedActionPolicyService.canScheduleRunLater(channelType, transportChatId)) {
            return ToolResult.failure(ToolFailureKind.POLICY_DENIED,
                    "run_later is unavailable for this channel or session");
        }

        Instant runAt = resolveRunAt(parameters);
        if (runAt == null) {
            return ToolResult.failure("Provide either delay_seconds or run_at");
        }

        DelayedActionDeliveryMode deliveryMode = resolveDeliveryMode(kind, parameters);
        if (deliveryMode == null) {
            return ToolResult.failure("Could not resolve delivery mode for action");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        putIfNotBlank(payload, "message", stringParam(parameters, "message"));
        putIfNotBlank(payload, "instruction", stringParam(parameters, "instruction"));
        putIfNotBlank(payload, "artifactPath", stringParam(parameters, "artifact_path"));
        putIfNotBlank(payload, "artifactName", stringParam(parameters, "artifact_name"));
        putIfNotBlank(payload, "originalSummary", stringParam(parameters, "original_summary"));

        if (kind == DelayedActionKind.REMIND_LATER && !payload.containsKey("message")) {
            return ToolResult.failure("message is required for remind_later");
        }
        if (kind == DelayedActionKind.RUN_LATER && !payload.containsKey("instruction")) {
            return ToolResult.failure("instruction is required for run_later");
        }
        if (kind == DelayedActionKind.NOTIFY_JOB_READY
                && !payload.containsKey("message")
                && !payload.containsKey("artifactPath")) {
            return ToolResult.failure("message or artifact_path is required for notify_job_ready");
        }
        String humanSummary = resolveHumanSummary(kind, payload);
        String userVisibleKind = resolveUserVisibleKind(kind);
        String nextCheckLabel = runAt.toString();
        payload.put("humanSummary", humanSummary);
        payload.put("userVisibleKind", userVisibleKind);
        payload.put("nextCheckLabel", nextCheckLabel);
        if (!supportsDeliveryNow(channelType, transportChatId, deliveryMode)) {
            return ToolResult.failure(ToolFailureKind.POLICY_DENIED,
                    unavailableMessageFor(deliveryMode));
        }

        DelayedSessionAction created;
        try {
            created = delayedActionService.schedule(DelayedSessionAction.builder()
                    .channelType(channelType)
                    .conversationKey(conversationKey)
                    .transportChatId(transportChatId)
                    .jobId(stringParam(parameters, "job_id"))
                    .kind(kind)
                    .deliveryMode(deliveryMode)
                    .runAt(runAt)
                    .maxAttempts(intParam(parameters, "max_attempts",
                            runtimeConfigService.getDelayedActionsDefaultMaxAttempts()))
                    .dedupeKey(stringParam(parameters, "dedupe_key"))
                    .cancelOnUserActivity(resolveCancelOnUserActivity(parameters, kind))
                    .createdBy("tool:" + TOOL_NAME)
                    .payload(payload)
                    .build());
        } catch (RuntimeException e) {
            return ToolResult.failure("Failed to schedule delayed action: " + e.getMessage());
        }

        boolean proactiveSupportedNow = switch (deliveryMode) {
        case DIRECT_FILE -> delayedActionPolicyService.supportsProactiveDocument(channelType, transportChatId);
        case DIRECT_MESSAGE -> delayedActionPolicyService.supportsProactiveMessage(channelType, transportChatId);
        case INTERNAL_TURN -> delayedActionPolicyService.supportsDelayedExecution(channelType, transportChatId);
        };
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionId", created.getId());
        data.put("kind", kind.name().toLowerCase(java.util.Locale.ROOT));
        data.put("deliveryMode", created.getDeliveryMode().name());
        data.put("resolvedRunAt", created.getRunAt().toString());
        data.put("proactiveDeliverySupportedNow", proactiveSupportedNow);
        data.put("cancelOnUserActivity", created.isCancelOnUserActivity());
        data.put("humanSummary", payloadString(created, "humanSummary"));
        data.put("userVisibleKind", payloadString(created, "userVisibleKind"));
        data.put("nextCheckLabel", payloadString(created, "nextCheckLabel"));
        return ToolResult.success("Delayed action scheduled for " + created.getRunAt(), data);
    }

    private boolean supportsDeliveryNow(String channelType, String transportChatId,
            DelayedActionDeliveryMode deliveryMode) {
        return switch (deliveryMode) {
        case DIRECT_FILE -> delayedActionPolicyService.supportsProactiveDocument(channelType, transportChatId);
        case DIRECT_MESSAGE -> delayedActionPolicyService.supportsProactiveMessage(channelType, transportChatId);
        case INTERNAL_TURN -> delayedActionPolicyService.supportsDelayedExecution(channelType, transportChatId);
        };
    }

    private String unavailableMessageFor(DelayedActionDeliveryMode deliveryMode) {
        return switch (deliveryMode) {
        case DIRECT_FILE -> "Proactive file delivery is unavailable for this channel or session";
        case DIRECT_MESSAGE -> "Proactive message delivery is unavailable for this channel or session";
        case INTERNAL_TURN -> "Delayed execution is unavailable for this channel or session";
        };
    }

    private ToolResult listActions(String channelType, String conversationKey) {
        List<DelayedSessionAction> actions = delayedActionService.listActions(channelType, conversationKey);
        List<Map<String, Object>> items = actions.stream()
                .map(action -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", action.getId());
                    item.put("kind", action.getKind().name());
                    item.put("status", action.getStatus().name());
                    item.put("runAt", action.getRunAt() != null ? action.getRunAt().toString() : "");
                    item.put("deliveryMode", action.getDeliveryMode().name());
                    item.put("attempts", action.getAttempts());
                    item.put("cancelOnUserActivity", action.isCancelOnUserActivity());
                    item.put("humanSummary", firstNonBlank(
                            payloadString(action, "humanSummary"),
                            resolveHumanSummary(action.getKind(), action.getPayload())));
                    item.put("userVisibleKind", firstNonBlank(
                            payloadString(action, "userVisibleKind"),
                            resolveUserVisibleKind(action.getKind())));
                    item.put("nextCheckLabel", firstNonBlank(
                            payloadString(action, "nextCheckLabel"),
                            action.getRunAt() != null ? action.getRunAt().toString() : null));
                    return item;
                })
                .toList();
        return ToolResult.success("Found " + actions.size() + " delayed actions", Map.of("items", items));
    }

    private ToolResult cancelAction(Map<String, Object> parameters, String channelType, String conversationKey) {
        String actionId = stringParam(parameters, "action_id");
        if (StringValueSupport.isBlank(actionId)) {
            return ToolResult.failure("Missing required parameter: action_id");
        }
        boolean cancelled = delayedActionService.cancelAction(actionId, channelType, conversationKey);
        if (!cancelled) {
            return ToolResult.failure(ToolFailureKind.POLICY_DENIED, "Delayed action not found or not cancellable");
        }
        return ToolResult.success("Delayed action cancelled", Map.of("actionId", actionId));
    }

    private ToolResult runNow(Map<String, Object> parameters, String channelType, String conversationKey) {
        String actionId = stringParam(parameters, "action_id");
        if (StringValueSupport.isBlank(actionId)) {
            return ToolResult.failure("Missing required parameter: action_id");
        }
        boolean scheduled = delayedActionService.runNow(actionId, channelType, conversationKey);
        if (!scheduled) {
            return ToolResult.failure(ToolFailureKind.POLICY_DENIED, "Delayed action not found or not runnable");
        }
        return ToolResult.success("Delayed action made due immediately", Map.of("actionId", actionId));
    }

    private DelayedActionDeliveryMode resolveDeliveryMode(DelayedActionKind kind, Map<String, Object> parameters) {
        return switch (kind) {
        case REMIND_LATER -> DelayedActionDeliveryMode.DIRECT_MESSAGE;
        case RUN_LATER -> DelayedActionDeliveryMode.INTERNAL_TURN;
        case NOTIFY_JOB_READY -> !StringValueSupport.isBlank(stringParam(parameters, "artifact_path"))
                ? DelayedActionDeliveryMode.DIRECT_FILE
                : DelayedActionDeliveryMode.DIRECT_MESSAGE;
        };
    }

    private Instant resolveRunAt(Map<String, Object> parameters) {
        String runAt = stringParam(parameters, "run_at");
        Integer delaySeconds = nullableIntParam(parameters, "delay_seconds");
        if (!StringValueSupport.isBlank(runAt)) {
            try {
                return Instant.parse(runAt.trim());
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        if (delaySeconds != null && delaySeconds > 0) {
            return Instant.now(clock).plusSeconds(delaySeconds);
        }
        return null;
    }

    private String stringParam(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue.trim() : null;
    }

    private String payloadString(DelayedSessionAction action, String key) {
        if (action == null || action.getPayload() == null) {
            return null;
        }
        Object value = action.getPayload().get(key);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue.trim() : null;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (!StringValueSupport.isBlank(value)) {
            target.put(key, value.trim());
        }
    }

    private boolean resolveCancelOnUserActivity(Map<String, Object> parameters, DelayedActionKind kind) {
        boolean defaultValue = kind == DelayedActionKind.RUN_LATER;
        return boolParam(parameters, "cancel_on_user_activity", defaultValue);
    }

    private String resolveHumanSummary(DelayedActionKind kind, Map<String, Object> payload) {
        if (kind == null) {
            return "Delayed action";
        }
        Map<String, Object> safePayload = payload != null ? payload : Map.of();
        String message = stringValue(safePayload.get("message"));
        String originalSummary = stringValue(safePayload.get("originalSummary"));
        return switch (kind) {
        case REMIND_LATER -> message != null ? "Reminder: " + message : "Reminder";
        case RUN_LATER -> originalSummary != null
                ? "Waiting for result: " + originalSummary
                : "Check back later";
        case NOTIFY_JOB_READY -> message != null
                ? message
                : originalSummary != null ? "Result ready: " + originalSummary : "Job result ready";
        };
    }

    private String resolveUserVisibleKind(DelayedActionKind kind) {
        return switch (kind) {
        case REMIND_LATER -> "reminder";
        case RUN_LATER -> "check_back";
        case NOTIFY_JOB_READY -> "job_result";
        };
    }

    private String stringValue(Object value) {
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue.trim() : null;
    }

    private String firstNonBlank(String primary, String fallback) {
        return !StringValueSupport.isBlank(primary) ? primary : fallback;
    }

    private boolean boolParam(Map<String, Object> parameters, String key, boolean defaultValue) {
        Object value = parameters.get(key);
        return value instanceof Boolean booleanValue ? booleanValue : defaultValue;
    }

    private int intParam(Map<String, Object> parameters, String key, int defaultValue) {
        Integer value = nullableIntParam(parameters, key);
        return value != null ? value : defaultValue;
    }

    private Integer nullableIntParam(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }
}
