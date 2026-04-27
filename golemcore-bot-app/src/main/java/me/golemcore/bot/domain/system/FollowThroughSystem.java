package me.golemcore.bot.domain.system;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.resilience.ClassifierRequest;
import me.golemcore.bot.domain.resilience.followthrough.ClassifierVerdict;
import me.golemcore.bot.domain.resilience.followthrough.FollowThroughClassifier;
import me.golemcore.bot.domain.scheduling.InternalTurnService;
import me.golemcore.bot.domain.resilience.ResilienceObservabilitySupport;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
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
@Slf4j
public class FollowThroughSystem extends AbstractResilienceContinuationSystem {

    static final String SAFE_CONTINUATION_PROMPT = "Continue with the concrete next action you just promised, using only the latest user request and visible conversation context. Do not broaden scope. If the action is destructive, asks for credentials, sends external messages, modifies production, or is ambiguous, ask the real user for confirmation.";
    static final String OBSERVED_DEPTH_EXCEEDED = "observability.follow_through.depth_exceeded";

    private final FollowThroughClassifier classifier;
    private final InternalTurnService internalTurnService;

    public FollowThroughSystem(FollowThroughClassifier classifier,
            InternalTurnService internalTurnService,
            RuntimeConfigService runtimeConfigService,
            TraceService traceService,
            TraceSnapshotCodecPort traceSnapshotCodecPort,
            Clock clock) {
        super(runtimeConfigService, traceService, traceSnapshotCodecPort, clock);
        this.classifier = classifier;
        this.internalTurnService = internalTurnService;
    }

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
        return shouldProcessBase(log, context,
                ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED,
                ContextAttributes.RESILIENCE_FOLLOW_THROUGH_CHAIN_DEPTH,
                OBSERVED_DEPTH_EXCEEDED,
                "follow_through",
                resolveMaxChainDepth(runtimeConfigService.getFollowThroughConfig().getMaxChainDepth(), 1));
    }

    @Override
    public AgentContext process(AgentContext context) {
        if (!shouldProcess(context)) {
            return context;
        }
        RuntimeConfig.FollowThroughConfig cfg = runtimeConfigService.getFollowThroughConfig();
        String modelTier = cfg.getModelTier();
        Duration timeout = Duration.ofSeconds(cfg.getTimeoutSeconds());
        int incomingDepth = readIncomingChainDepth(context, ContextAttributes.RESILIENCE_FOLLOW_THROUGH_CHAIN_DEPTH);

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
                "follow_through.classifier.request",
                buildTracePayload("follow_through", request, modelTier, timeout, incomingDepth));

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

        if (isTimeoutReason(verdict.reason())) {
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
}
