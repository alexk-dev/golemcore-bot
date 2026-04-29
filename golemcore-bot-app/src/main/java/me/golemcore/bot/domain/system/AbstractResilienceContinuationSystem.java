package me.golemcore.bot.domain.system;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.resilience.ClassifierRequest;
import me.golemcore.bot.domain.resilience.ResilienceObservabilitySupport;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.tracing.TraceService;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

abstract class AbstractResilienceContinuationSystem implements AgentSystem {

    protected final RuntimeConfigService runtimeConfigService;
    protected final TraceService traceService;
    protected final TraceSnapshotCodecPort traceSnapshotCodecPort;
    protected final Clock clock;

    protected AbstractResilienceContinuationSystem(RuntimeConfigService runtimeConfigService,
            TraceService traceService,
            TraceSnapshotCodecPort traceSnapshotCodecPort,
            Clock clock) {
        this.runtimeConfigService = runtimeConfigService;
        this.traceService = traceService;
        this.traceSnapshotCodecPort = traceSnapshotCodecPort;
        this.clock = clock;
    }

    protected boolean shouldProcessBase(Logger log,
            AgentContext context,
            String scheduledAttributeKey,
            String chainDepthAttributeKey,
            String observedDepthExceededKey,
            String driver,
            int maxChainDepth) {
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
        if (Boolean.TRUE.equals(context.getAttribute(scheduledAttributeKey))) {
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
        int incomingDepth = readIncomingChainDepth(context, chainDepthAttributeKey);
        if (incomingDepth >= maxChainDepth) {
            if (ResilienceObservabilitySupport.markObservedOnce(context, observedDepthExceededKey)) {
                ResilienceObservabilitySupport.emitContextMetric(log, traceService, runtimeConfigService, clock,
                        context, "synthetic_turn.global_depth_exceeded",
                        Map.of(
                                "driver", driver,
                                "incoming_depth", incomingDepth,
                                "max_depth", maxChainDepth));
            }
            return false;
        }
        return true;
    }

    protected Map<String, Object> buildTracePayload(String driver,
            ClassifierRequest request,
            String modelTier,
            Duration timeout,
            int incomingDepth) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driver", driver);
        payload.put("model_tier", modelTier);
        payload.put("timeout_ms", timeout != null ? timeout.toMillis() : null);
        payload.put("incoming_depth", incomingDepth);
        payload.put("user_message", request.userMessage());
        payload.put("assistant_reply", request.assistantReply());
        payload.put("executed_tools_in_turn", request.executedToolsInTurn());
        return payload;
    }

    protected boolean isTimeoutReason(String reason) {
        return reason != null && reason.toLowerCase(Locale.ROOT).contains("timed out");
    }

    protected boolean toolsExecutedSinceLastUserMessage(AgentContext context) {
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

    protected List<String> executedToolsSinceLastUserMessage(AgentContext context) {
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

    protected String extractAssistantText(AgentContext context) {
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
            if ("assistant".equalsIgnoreCase(msg.getRole())
                    && msg.getContent() != null
                    && !msg.getContent().isBlank()) {
                return msg.getContent();
            }
        }
        return null;
    }

    protected String extractLastUserText(AgentContext context) {
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

    protected int readIncomingChainDepth(AgentContext context, String chainDepthAttributeKey) {
        Message lastUser = findLastUserMessage(context);
        if (lastUser == null || lastUser.getMetadata() == null) {
            return 0;
        }
        Object raw = lastUser.getMetadata().get(chainDepthAttributeKey);
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        return 0;
    }

    protected Message findLastUserMessage(AgentContext context) {
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

    protected int resolveMaxChainDepth(Integer configuredMax, int defaultValue) {
        return configuredMax != null && configuredMax >= 0 ? configuredMax : defaultValue;
    }

    protected boolean isAutoModeContext(AgentContext context) {
        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.AUTO_MODE))) {
            return true;
        }
        Message lastUser = findLastUserMessage(context);
        return lastUser != null && lastUser.getMetadata() != null
                && Boolean.TRUE.equals(lastUser.getMetadata().get(ContextAttributes.AUTO_MODE));
    }

    protected boolean isInterruptRequested(AgentContext context) {
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
