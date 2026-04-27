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
import me.golemcore.bot.domain.resilience.autoproceed.AutoProceedClassifier;
import me.golemcore.bot.domain.resilience.autoproceed.ClassifierVerdict;
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
 * Resilience Auto-Proceed classifier system.
 *
 * <p>
 * Runs after {@link FollowThroughSystem} (order 62). When the assistant's final
 * reply ended with a rhetorical confirmation question that has a single obvious
 * forward path (e.g. "Ready to continue?"), the classifier dispatches a
 * synthetic affirmative user message via {@link InternalTurnService} so the
 * agent keeps moving without waiting for human input.
 *
 * <p>
 * Hard guards (cheap, local) run first and short-circuit the classifier call
 * whenever auto-affirmation is obviously unsafe or redundant: tools already
 * executed in the turn, plan mode active, LLM error recorded, auto mode, /stop
 * pending, an already-scheduled follow-through nudge on the same turn, empty
 * assistant text, or the inbound chain depth already at the max.
 *
 * <p>
 * Off by default ? the operator must opt in via the Resilience dashboard.
 */
@Component
@Slf4j
public class AutoProceedSystem extends AbstractResilienceContinuationSystem {

    static final String SAFE_AFFIRMATION_PROMPT = "Proceed with the single non-destructive next step you just asked to continue.";
    static final String OBSERVED_DEPTH_EXCEEDED = "observability.auto_proceed.depth_exceeded";

    private final AutoProceedClassifier classifier;
    private final InternalTurnService internalTurnService;

    public AutoProceedSystem(AutoProceedClassifier classifier,
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
        return "AutoProceedSystem";
    }

    @Override
    public int getOrder() {
        return 62;
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isAutoProceedEnabled();
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        if (context == null || !isEnabled()) {
            return false;
        }
        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_SCHEDULED))) {
            ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                    "auto_proceed.blocked_risk_guard",
                    Map.of("guard", "follow_through_already_scheduled"));
            return false;
        }
        return shouldProcessBase(log, context,
                ContextAttributes.RESILIENCE_AUTO_PROCEED_SCHEDULED,
                ContextAttributes.RESILIENCE_AUTO_PROCEED_CHAIN_DEPTH,
                OBSERVED_DEPTH_EXCEEDED,
                "auto_proceed",
                resolveMaxChainDepth(runtimeConfigService.getAutoProceedConfig().getMaxChainDepth(), 2));
    }

    @Override
    public AgentContext process(AgentContext context) {
        if (!shouldProcess(context)) {
            return context;
        }
        RuntimeConfig.AutoProceedConfig cfg = runtimeConfigService.getAutoProceedConfig();
        String modelTier = cfg.getModelTier();
        Duration timeout = Duration.ofSeconds(cfg.getTimeoutSeconds());
        int incomingDepth = readIncomingChainDepth(context, ContextAttributes.RESILIENCE_AUTO_PROCEED_CHAIN_DEPTH);

        ClassifierRequest request = new ClassifierRequest(
                extractLastUserText(context),
                extractAssistantText(context),
                executedToolsSinceLastUserMessage(context));
        ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                "follow_through.classifier.invoked",
                Map.of(
                        "driver", "auto_proceed",
                        "incoming_depth", incomingDepth,
                        "model_tier", modelTier));
        ResilienceObservabilitySupport.captureSampledPayload(log, traceService, traceSnapshotCodecPort,
                runtimeConfigService, clock, context, "auto_proceed.classifier",
                "auto_proceed.classifier.request",
                buildTracePayload("auto_proceed", request, modelTier, timeout, incomingDepth));

        ClassifierVerdict verdict = classifier.classify(request, modelTier, timeout);
        ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                "follow_through.verdict.intent_type",
                Map.of(
                        "driver", "auto_proceed",
                        "intent_type", verdict.intentType(),
                        "should_auto_affirm", verdict.shouldAutoAffirm(),
                        "risk_level", verdict.riskLevel()));
        ResilienceObservabilitySupport.captureSampledPayload(log, traceService, traceSnapshotCodecPort,
                runtimeConfigService, clock, context, "auto_proceed.classifier",
                "auto_proceed.classifier.verdict", buildVerdictTracePayload(verdict));

        if (isTimeoutReason(verdict.reason())) {
            ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                    "follow_through.classifier.timeout",
                    Map.of(
                            "driver", "auto_proceed",
                            "model_tier", modelTier));
        }

        if (!verdict.shouldAutoAffirm()) {
            log.debug("[AutoProceed] classifier declined to affirm (intent={}, reason={})",
                    verdict.intentType(), verdict.reason());
            return context;
        }
        if (verdict.riskLevel() != null && verdict.riskLevel().isHighRisk()) {
            log.debug("[AutoProceed] classifier marked rhetorical confirm high risk; skipping affirmation (reason={})",
                    verdict.reason());
            ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                    "auto_proceed.blocked_risk_guard",
                    Map.of(
                            "guard", "classifier_high_risk",
                            "intent_type", verdict.intentType()));
            return context;
        }
        if (isInterruptRequested(context)) {
            log.debug("[AutoProceed] skipping affirmation dispatch: interrupt requested mid-classifier");
            ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                    "follow_through.nudge.canceled_user_activity",
                    Map.of(
                            "driver", "auto_proceed",
                            "cancel_reason", "interrupt_requested"));
            return context;
        }

        boolean scheduled = internalTurnService.scheduleAutoProceedAffirmation(
                context, SAFE_AFFIRMATION_PROMPT, incomingDepth);
        if (scheduled) {
            context.setAttribute(ContextAttributes.RESILIENCE_AUTO_PROCEED_SCHEDULED, true);
            ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                    "auto_proceed.affirmation.scheduled",
                    Map.of("next_depth", incomingDepth + 1));
            log.info("[AutoProceed] affirmation scheduled (sessionId={}, nextDepth={})",
                    context.getSession() != null ? context.getSession().getId() : "?",
                    incomingDepth + 1);
        } else {
            ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock, context,
                    "follow_through.nudge.dispatch_failed",
                    Map.of(
                            "driver", "auto_proceed",
                            "incoming_depth", incomingDepth));
        }
        return context;
    }

    private Map<String, Object> buildVerdictTracePayload(ClassifierVerdict verdict) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driver", "auto_proceed");
        payload.put("intent_type", verdict.intentType());
        payload.put("should_auto_affirm", verdict.shouldAutoAffirm());
        payload.put("risk_level", verdict.riskLevel());
        payload.put("question_text", verdict.questionText());
        payload.put("reason", verdict.reason());
        return payload;
    }
}
