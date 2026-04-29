package me.golemcore.bot.domain.loop;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.SkillTransitionRequest;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.context.hygiene.ContextHygieneService;
import me.golemcore.bot.domain.tracing.MdcSupport;
import me.golemcore.bot.domain.runtimeconfig.ModelRoutingConfigView;
import me.golemcore.bot.domain.runtimeconfig.TracingConfigView;
import me.golemcore.bot.domain.tracing.TraceMdcSupport;
import me.golemcore.bot.domain.tracing.TraceService;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.domain.system.ResponseRoutingAgentSystem;

/**
 * Runs ordered agent systems and owns pipeline trace-state events.
 */
@Slf4j
final class AgentPipelineRunner {

    private final AgentPipelinePlan plan;
    private final ModelRoutingConfigView modelRoutingConfigView;
    private final TracingConfigView tracingConfigView;
    private final UserPreferencesService preferencesService;
    private final Clock clock;
    private final TraceService traceService;
    private final ContextHygieneService contextHygieneService;

    AgentPipelineRunner(AgentPipelinePlan plan, ModelRoutingConfigView modelRoutingConfigView,
            TracingConfigView tracingConfigView, UserPreferencesService preferencesService, Clock clock,
            TraceService traceService, ContextHygieneService contextHygieneService) {
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.modelRoutingConfigView = modelRoutingConfigView;
        this.tracingConfigView = tracingConfigView;
        this.preferencesService = preferencesService;
        this.clock = clock;
        this.traceService = traceService;
        this.contextHygieneService = contextHygieneService;
    }

    void initializeRoutingSystem() {
        log.info("[AgentPipelineRunner] routingSystem resolved: {}",
                plan.routingSystem().map(system -> system.getClass().getName()).orElse("<null>"));
    }

    AgentContext run(AgentContext context) {
        int maxIterations = context.getMaxIterations();
        boolean reachedLimit = false;

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            context.setCurrentIteration(iteration);
            log.info("--- Iteration {}/{} ---", iteration + 1, maxIterations);

            for (AgentSystem system : plan.orderedSystems()) {
                context = processSystem(context, iteration, system);
            }

            if (!shouldContinueLoop(context)) {
                log.info("Agent loop completed after {} iteration(s)", iteration + 1);
                break;
            }

            if (iteration + 1 >= maxIterations) {
                reachedLimit = true;
                log.warn("Reached max iterations limit ({}), stopping", maxIterations);
                break;
            }

            log.debug("Continuing to next iteration");
            context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, false);
            context.setOutgoingResponse(null);
            context.getToolResults().clear();
            contextHygieneService.afterOuterIteration(context);
        }

        if (reachedLimit) {
            context.setAttribute(ContextAttributes.ITERATION_LIMIT_REACHED, true);
            context.clearSkillTransitionRequest();
            String limitMessage = preferencesService.getMessage("system.iteration.limit", maxIterations);
            context = routeSyntheticAssistantResponse(context, limitMessage);
        }
        return context;
    }

    AgentContext routeResponse(AgentContext context) {
        ResponseRoutingAgentSystem routingSystem = plan.routingSystem().orElse(null);
        if (routingSystem == null) {
            log.warn("[AgentPipelineRunner] routingSystem is null; cannot route response");
            return context;
        }

        boolean should = routingSystem.shouldProcess(context);
        log.info("[AgentPipelineRunner] routingSystem.shouldProcess={}", should);
        if (should) {
            log.info("[AgentPipelineRunner] invoking routingSystem.process (OutgoingResponse present: {})",
                    context.getAttribute(ContextAttributes.OUTGOING_RESPONSE) != null);
            return routingSystem.process(context);
        }
        return context;
    }

    AgentContext routeSyntheticAssistantResponse(AgentContext context, String content) {
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(content));
        return routeResponse(context);
    }

    private AgentContext processSystem(AgentContext context, int iteration, AgentSystem system) {
        if (!system.isEnabled()) {
            log.debug("System '{}' is disabled, skipping", system.getName());
            return context;
        }

        if (!system.shouldProcess(context)) {
            log.debug("System '{}' shouldProcess=false, skipping", system.getName());
            return context;
        }

        log.debug("Running system: {} (order={})", system.getName(), system.getOrder());
        long startMs = clock.millis();
        TraceStateSnapshot beforeTraceState = captureTraceState(context);
        TraceContext systemSpan = startChildSpan(context, "system." + system.getName(), TraceSpanKind.INTERNAL,
                Map.of("system.name", system.getName(), "system.order", system.getOrder(), "iteration", iteration));
        try {
            try (MdcSupport.Scope ignored = MdcSupport.withContext(buildTraceMdcContext(systemSpan, context))) {
                context = system.process(context);
            }
            emitTraceStateEvents(context, system.getName(), systemSpan, beforeTraceState);
            finishChildSpan(context, systemSpan, TraceStatusCode.OK, null);
            contextHygieneService.afterSystem(context, system.getName());
            log.debug("System '{}' completed in {}ms", system.getName(), clock.millis() - startMs);
        } catch (Exception e) {
            finishChildSpan(context, systemSpan, TraceStatusCode.ERROR, e.getMessage());
            log.error("System '{}' FAILED after {}ms: {}", system.getName(), clock.millis() - startMs, e.getMessage(),
                    e);
            context.addFailure(new FailureEvent(FailureSource.SYSTEM, system.getName(), FailureKind.EXCEPTION,
                    e.getMessage(), clock.instant()));
        }
        return context;
    }

    private boolean shouldContinueLoop(AgentContext context) {
        SkillTransitionRequest transition = context.getSkillTransitionRequest();
        return transition != null && transition.targetSkill() != null;
    }

    private TraceContext startChildSpan(AgentContext context, String spanName, TraceSpanKind spanKind,
            Map<String, Object> attributes) {
        if (context == null || context.getSession() == null || context.getTraceContext() == null
                || tracingConfigView == null || !tracingConfigView.isTracingEnabled()) {
            return null;
        }
        return traceService.startSpan(context.getSession(), context.getTraceContext(), spanName, spanKind,
                clock.instant(), attributes);
    }

    private void finishChildSpan(AgentContext context, TraceContext spanContext, TraceStatusCode statusCode,
            String statusMessage) {
        if (context == null || context.getSession() == null || spanContext == null) {
            return;
        }
        traceService.finishSpan(context.getSession(), spanContext, statusCode, statusMessage, clock.instant());
    }

    private TraceStateSnapshot captureTraceState(AgentContext context) {
        if (context == null) {
            return new TraceStateSnapshot(null, "balanced", null, null, null, null, null);
        }
        String skillName = context.getActiveSkill() != null ? context.getActiveSkill().getName() : null;
        if ((skillName == null || skillName.isBlank()) && context.getAttributes() != null) {
            Object activeSkill = context.getAttributes().get(ContextAttributes.ACTIVE_SKILL_NAME);
            if (activeSkill instanceof String activeSkillName && !activeSkillName.isBlank()) {
                skillName = activeSkillName;
            }
        }
        String skillSource = stringAttribute(context, ContextAttributes.ACTIVE_SKILL_SOURCE);
        String tier = normalizeTierForTrace(context.getModelTier());
        String modelId = stringAttribute(context, ContextAttributes.MODEL_TIER_MODEL_ID);
        if (modelId == null || modelId.isBlank()) {
            modelId = resolveRouterModelId(tier);
        }
        String reasoning = stringAttribute(context, ContextAttributes.MODEL_TIER_REASONING);
        if (reasoning == null || reasoning.isBlank()) {
            reasoning = resolveRouterReasoning(tier);
        }
        return new TraceStateSnapshot(skillName, tier, modelId, reasoning,
                stringAttribute(context, ContextAttributes.MODEL_TIER_SOURCE), skillSource,
                context.getSkillTransitionRequest());
    }

    private void emitTraceStateEvents(AgentContext context, String systemName, TraceContext systemSpan,
            TraceStateSnapshot beforeState) {
        if (context == null || context.getSession() == null || systemSpan == null) {
            return;
        }
        TraceStateSnapshot afterState = captureTraceState(context);

        if (!Objects.equals(beforeState.transitionRequest(), afterState.transitionRequest())
                && afterState.transitionRequest() != null) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            String requesterSkill = beforeState.skillName() != null ? beforeState.skillName() : afterState.skillName();
            putIfPresent(attributes, "from_skill", requesterSkill);
            putIfPresent(attributes, "to_skill", afterState.transitionRequest().targetSkill());
            putIfPresent(attributes, "source", formatTransitionReason(afterState.transitionRequest()));
            putIfPresent(attributes, "reason", formatTransitionReason(afterState.transitionRequest()));
            emitTraceEvent(context, systemSpan, "skill.transition.requested", attributes);
        }

        if (!Objects.equals(beforeState.skillName(), afterState.skillName()) && afterState.skillName() != null) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            putIfPresent(attributes, "from_skill", beforeState.skillName());
            putIfPresent(attributes, "to_skill", afterState.skillName());
            String skillSource = afterState.skillSource() != null && !afterState.skillSource().isBlank()
                    ? afterState.skillSource()
                    : formatTransitionReason(beforeState.transitionRequest());
            putIfPresent(attributes, "source", skillSource);
            putIfPresent(attributes, "reason", skillSource);
            emitTraceEvent(context, systemSpan, "skill.transition.applied", attributes);
        }

        if ("ContextBuildingSystem".equals(systemName)) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            putIfPresent(attributes, "skill", afterState.skillName());
            putIfPresent(attributes, "tier", afterState.tier());
            putIfPresent(attributes, "model_id", afterState.modelId());
            putIfPresent(attributes, "reasoning", afterState.reasoning());
            putIfPresent(attributes, "source", afterState.source());
            emitTraceEvent(context, systemSpan, "tier.resolved", attributes);
            emitWebhookResponseSchemaContextEvent(context, systemSpan);
        }

        if (!Objects.equals(beforeState.tier(), afterState.tier())
                || !Objects.equals(beforeState.modelId(), afterState.modelId())) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            putIfPresent(attributes, "from_tier", beforeState.tier());
            putIfPresent(attributes, "to_tier", afterState.tier());
            putIfPresent(attributes, "from_model_id", beforeState.modelId());
            putIfPresent(attributes, "to_model_id", afterState.modelId());
            putIfPresent(attributes, "skill", afterState.skillName());
            putIfPresent(attributes, "source", afterState.source());
            emitTraceEvent(context, systemSpan, "tier.transition", attributes);
        }
    }

    private void emitTraceEvent(AgentContext context, TraceContext spanContext, String eventName,
            Map<String, Object> attributes) {
        if (traceService == null || context == null || context.getSession() == null || spanContext == null) {
            return;
        }
        traceService.appendEvent(context.getSession(), spanContext, eventName, clock.instant(), attributes);
    }

    private void emitWebhookResponseSchemaContextEvent(AgentContext context, TraceContext systemSpan) {
        Message lastMessage = lastContextMessage(context);
        String schemaText = readContextString(context, ContextAttributes.WEBHOOK_RESPONSE_JSON_SCHEMA_TEXT);
        if (schemaText == null || schemaText.isBlank()) {
            schemaText = readMetadataString(lastMessage, ContextAttributes.WEBHOOK_RESPONSE_JSON_SCHEMA_TEXT);
        }
        if (schemaText == null || schemaText.isBlank()) {
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("schema.present", true);
        attributes.put("schema.chars", schemaText.length());
        attributes.put("prompt.injected", context.getSystemPrompt() != null
                && context.getSystemPrompt().contains("Webhook Response JSON Contract"));
        String validationTier = readContextString(context, ContextAttributes.WEBHOOK_RESPONSE_VALIDATION_MODEL_TIER);
        if (validationTier == null || validationTier.isBlank()) {
            validationTier = readMetadataString(lastMessage, ContextAttributes.WEBHOOK_RESPONSE_VALIDATION_MODEL_TIER);
        }
        putIfPresent(attributes, "validation.model.tier", validationTier);
        putIfPresent(attributes, "response.model.tier", context.getModelTier());
        emitTraceEvent(context, systemSpan, "webhook.response.schema.instructions", attributes);
    }

    private Message lastContextMessage(AgentContext context) {
        if (context == null || context.getMessages() == null || context.getMessages().isEmpty()) {
            return null;
        }
        return context.getMessages().get(context.getMessages().size() - 1);
    }

    private String readMetadataString(Message message, String key) {
        if (message == null || message.getMetadata() == null || key == null || key.isBlank()) {
            return null;
        }
        return me.golemcore.bot.domain.autorun.AutoRunContextSupport.readMetadataString(message.getMetadata(), key);
    }

    private String normalizeTierForTrace(String tier) {
        if (tier == null || tier.isBlank() || "default".equalsIgnoreCase(tier)) {
            return "balanced";
        }
        String normalized = ModelTierCatalog.normalizeTierId(tier);
        return normalized != null ? normalized : tier;
    }

    private String stringAttribute(AgentContext context, String key) {
        return readContextString(context, key);
    }

    private String readContextString(AgentContext context, String key) {
        if (context == null || context.getAttributes() == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = context.getAttributes().get(key);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : null;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private String formatTransitionReason(SkillTransitionRequest request) {
        if (request == null || request.reason() == null) {
            return null;
        }
        return request.reason().name().toLowerCase(Locale.ROOT);
    }

    private String resolveRouterModelId(String tier) {
        if (modelRoutingConfigView == null) {
            return null;
        }
        return switch (tier) {
            case "smart" -> modelRoutingConfigView.getSmartModel();
            case "deep" -> modelRoutingConfigView.getDeepModel();
            case "coding" -> modelRoutingConfigView.getCodingModel();
            case "routing" -> modelRoutingConfigView.getRoutingModel();
            case "balanced" -> modelRoutingConfigView.getBalancedModel();
            default -> {
                RuntimeConfig.TierBinding binding = modelRoutingConfigView.getModelTierBinding(tier);
                yield binding != null ? binding.getModel() : null;
            }
        };
    }

    private String resolveRouterReasoning(String tier) {
        if (modelRoutingConfigView == null) {
            return null;
        }
        return switch (tier) {
            case "smart" -> modelRoutingConfigView.getSmartModelReasoning();
            case "deep" -> modelRoutingConfigView.getDeepModelReasoning();
            case "coding" -> modelRoutingConfigView.getCodingModelReasoning();
            case "routing" -> modelRoutingConfigView.getRoutingModelReasoning();
            case "balanced" -> modelRoutingConfigView.getBalancedModelReasoning();
            default -> {
                RuntimeConfig.TierBinding binding = modelRoutingConfigView.getModelTierBinding(tier);
                yield binding != null ? binding.getReasoning() : null;
            }
        };
    }

    private Map<String, String> buildTraceMdcContext(TraceContext spanContext, AgentContext context) {
        if (spanContext == null) {
            return Map.of();
        }
        Map<String, Object> source = context != null ? context.getAttributes() : Map.of();
        return TraceMdcSupport.buildMdcContext(spanContext, source);
    }

    private record TraceStateSnapshot(String skillName, String tier, String modelId, String reasoning, String source,
            String skillSource, SkillTransitionRequest transitionRequest) {
    }
}
