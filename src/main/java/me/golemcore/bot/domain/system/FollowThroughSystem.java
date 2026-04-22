package me.golemcore.bot.domain.system;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.resilience.followthrough.ClassifierRequest;
import me.golemcore.bot.domain.resilience.followthrough.ClassifierVerdict;
import me.golemcore.bot.domain.resilience.followthrough.FollowThroughClassifier;
import me.golemcore.bot.domain.service.InternalTurnService;
import me.golemcore.bot.domain.service.ResilienceObservabilitySupport;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resilience follow-through classifier system.
 *
 * <p>
 * Runs after {@link ResponseRoutingSystem} (order 61) so the user sees the
 * assistant reply immediately while the classifier decides whether the reply
 * contained an unfulfilled commitment. If the classifier says yes, a synthetic
 * user message is dispatched via {@link InternalTurnService} to unblock the
 * agent on the next turn.
 *
 * <p>
 * Hard guards (cheap, local) run first and short-circuit the classifier call
 * whenever a nudge is obviously unneeded or unsafe: tools already executed,
 * plan mode active, LLM error recorded, auto mode, empty assistant text, or the
 * inbound message was itself an internal retry that reached the max chain
 * depth.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FollowThroughSystem implements AgentSystem {

    static final String SAFE_CONTINUATION_PROMPT = "Continue with the concrete next action you just promised, using only the latest user request and visible conversation context. Do not broaden scope. If the action is destructive, asks for credentials, sends external messages, modifies production, or is ambiguous, ask the real user for confirmation.";
    static final String OBSERVED_DEPTH_EXCEEDED = "observability.follow_through.depth_exceeded";

    private final FollowThroughClassifier classifier;
    private final InternalTurnService internalTurnService;
    private final RuntimeConfigService runtimeConfigService;
    private final TraceService traceService;
    private final TraceSnapshotCodecPort traceSnapshotCodecPort;
    private final Clock clock;

    @Override
    public String getName() {
        return "FollowThroughSystem";
    }

    @Override
    public int getOrder() {
        return 61;
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isFollowThroughEnabled();
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        if (context == null || !isEnabled()) {
            return false;
        }
        if (context.getAttribute(ContextAttributes.LLM_ERROR) != null) {
            return false;
        }
        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.PLAN_MODE_ACTIVE))
                || context.getAttribute(ContextAttributes.PLAN_APPROVAL_NEEDED) != null) {
            return false;
        }
        if (isInterruptRequested(context)) {
            return false;
        }
        if (isAutoModeContext(context)) {
            return false;
        }
        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED))) {
            return false;
        }
        if (toolsExecutedSinceLastUserMessage(context)) {
            return false;
        }
        String assistantText = extractAssistantText(context);
        if (assistantText == null || assistantText.isBlank()) {
            return false;
        }
        String userText = extractLastUserText(context);
        if (userText == null || userText.isBlank()) {
            return false;
        }
        int incomingDepth = readIncomingChainDepth(context);
        int maxChainDepth = resolveMaxChainDepth();
        if (incomingDepth >= maxChainDepth) {
            if (ResilienceObservabilitySupport.markObservedOnce(context, OBSERVED_DEPTH_EXCEEDED)) {
                ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock,
                        context, "synthetic_turn.global_depth_exceeded",
                        Map.of(
                                "driver", "follow_through",
                                "incoming_depth", incomingDepth,
                                "max_depth", maxChainDepth));
            }
            return false;
        }
        return true;
    }

    @Override
    public AgentContext process(AgentContext context) {
        if (!shouldProcess(context)) {
            return context;
        }
        RuntimeConfig.FollowThroughConfig cfg = runtimeConfigService.getFollowThroughConfig();
        String modelTier = cfg.getModelTier();
        Duration timeout = Duration.ofSeconds(cfg.getTimeoutSeconds());
        int incomingDepth = readIncomingChainDepth(context);

        ClassifierRequest request = new ClassifierRequest(
                extractLastUserText(context),
                extractAssistantText(context),
                executedToolsSinceLastUserMessage(context));
        ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                "follow_through.classifier.invoked",
                Map.of(
                        "incoming_depth", incomingDepth,
                        "model_tier", modelTier));
        ResilienceObservabilitySupport.captureSampledPayload(log, traceService, traceSnapshotCodecPort,
                runtimeConfigService, clock, context, "follow_through.classifier",
                "follow_through.classifier.request", buildTracePayload(request, modelTier, timeout, incomingDepth));

        ClassifierVerdict verdict = classifier.classify(request, modelTier, timeout);
        ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                "follow_through.verdict.intent_type",
                Map.of(
                        "intent_type", verdict.intentType(),
                        "has_unfulfilled_commitment", verdict.hasUnfulfilledCommitment(),
                        "risk_level", verdict.riskLevel()));
        ResilienceObservabilitySupport.captureSampledPayload(log, traceService, traceSnapshotCodecPort,
                runtimeConfigService, clock, context, "follow_through.classifier",
                "follow_through.classifier.verdict", buildVerdictTracePayload(verdict));

        if (isTimeoutVerdict(verdict)) {
            ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                    "follow_through.classifier.timeout",
                    Map.of("model_tier", modelTier));
        }

        if (!verdict.hasUnfulfilledCommitment()) {
            log.debug("[FollowThrough] classifier declined to nudge (intent={}, reason={})",
                    verdict.intentType(), verdict.reason());
            return context;
        }
        if (verdict.riskLevel() != null && verdict.riskLevel().isHighRisk()) {
            log.debug("[FollowThrough] classifier marked commitment high risk; skipping nudge (reason={})",
                    verdict.reason());
            return context;
        }
        if (isInterruptRequested(context)) {
            log.debug("[FollowThrough] skipping nudge dispatch: interrupt requested mid-classifier");
            ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                    "follow_through.nudge.canceled_user_activity",
                    Map.of("cancel_reason", "interrupt_requested"));
            return context;
        }

        boolean scheduled = internalTurnService.scheduleFollowThroughNudge(
                context, SAFE_CONTINUATION_PROMPT, incomingDepth);
        if (scheduled) {
            context.setAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED, true);
            ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                    "follow_through.nudge.scheduled",
                    Map.of("next_depth", incomingDepth + 1));
            log.info("[FollowThrough] nudge scheduled (sessionId={}, nextDepth={})",
                    context.getSession() != null ? context.getSession().getId() : "?",
                    incomingDepth + 1);
        } else {
            ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                    "follow_through.nudge.dispatch_failed",
                    Map.of("incoming_depth", incomingDepth));
        }
        return context;
    }

    private Map<String, Object> buildTracePayload(ClassifierRequest request, String modelTier, Duration timeout,
            int incomingDepth) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driver", "follow_through");
        payload.put("model_tier", modelTier);
        payload.put("timeout_ms", timeout != null ? timeout.toMillis() : null);
        payload.put("incoming_depth", incomingDepth);
        payload.put("user_message", request.userMessage());
        payload.put("assistant_reply", request.assistantReply());
        payload.put("executed_tools_in_turn", request.executedToolsInTurn());
        return payload;
    }

    private Map<String, Object> buildVerdictTracePayload(ClassifierVerdict verdict) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driver", "follow_through");
        payload.put("intent_type", verdict.intentType());
        payload.put("has_unfulfilled_commitment", verdict.hasUnfulfilledCommitment());
        payload.put("commitment_category", verdict.commitmentCategory());
        payload.put("risk_level", verdict.riskLevel());
        payload.put("commitment_text", verdict.commitmentText());
        payload.put("reason", verdict.reason());
        return payload;
    }

    private boolean isTimeoutVerdict(ClassifierVerdict verdict) {
        return verdict != null
                && verdict.reason() != null
                && verdict.reason().toLowerCase(java.util.Locale.ROOT).contains("timed out");
    }

    private boolean toolsExecutedSinceLastUserMessage(AgentContext context) {
        List<Message> messages = context.getMessages();
        if (messages == null) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if ("user".equalsIgnoreCase(msg.getRole())) {
                return false;
            }
            if ("tool".equalsIgnoreCase(msg.getRole())) {
                return true;
            }
            if ("assistant".equalsIgnoreCase(msg.getRole())
                    && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<String> executedToolsSinceLastUserMessage(AgentContext context) {
        List<String> names = new ArrayList<>();
        List<Message> messages = context.getMessages();
        if (messages == null) {
            return names;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if ("user".equalsIgnoreCase(msg.getRole())) {
                break;
            }
            if ("tool".equalsIgnoreCase(msg.getRole()) && msg.getToolName() != null) {
                names.add(0, msg.getToolName());
            }
        }
        return names;
    }

    private String extractAssistantText(AgentContext context) {
        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        if (response != null && response.getContent() != null && !response.getContent().isBlank()) {
            return response.getContent();
        }
        List<Message> messages = context.getMessages();
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if ("assistant".equalsIgnoreCase(msg.getRole()) && msg.getContent() != null
                    && !msg.getContent().isBlank()) {
                return msg.getContent();
            }
        }
        return null;
    }

    private String extractLastUserText(AgentContext context) {
        List<Message> messages = context.getMessages();
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if ("user".equalsIgnoreCase(msg.getRole()) && msg.getContent() != null) {
                return msg.getContent();
            }
        }
        return null;
    }

    private int readIncomingChainDepth(AgentContext context) {
        Message lastUser = findLastUserMessage(context);
        if (lastUser == null || lastUser.getMetadata() == null) {
            return 0;
        }
        Object raw = lastUser.getMetadata().get(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_CHAIN_DEPTH);
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        return 0;
    }

    private Message findLastUserMessage(AgentContext context) {
        List<Message> messages = context.getMessages();
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if ("user".equalsIgnoreCase(msg.getRole())) {
                return msg;
            }
        }
        return null;
    }

    private int resolveMaxChainDepth() {
        RuntimeConfig.FollowThroughConfig cfg = runtimeConfigService.getFollowThroughConfig();
        Integer max = cfg.getMaxChainDepth();
        return max != null && max >= 0 ? max : 1;
    }

    private boolean isAutoModeContext(AgentContext context) {
        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.AUTO_MODE))) {
            return true;
        }
        Message lastUser = findLastUserMessage(context);
        return lastUser != null && lastUser.getMetadata() != null
                && Boolean.TRUE.equals(lastUser.getMetadata().get(ContextAttributes.AUTO_MODE));
    }

    private boolean isInterruptRequested(AgentContext context) {
        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.TURN_INTERRUPT_REQUESTED))) {
            return true;
        }
        if (context.getSession() == null || context.getSession().getMetadata() == null) {
            return false;
        }
        Object raw = context.getSession().getMetadata().get(ContextAttributes.TURN_INTERRUPT_REQUESTED);
        return Boolean.TRUE.equals(raw);
    }
}
