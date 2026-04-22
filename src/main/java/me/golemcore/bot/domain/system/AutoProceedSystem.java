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
import me.golemcore.bot.domain.resilience.autoproceed.AutoProceedClassifier;
import me.golemcore.bot.domain.resilience.autoproceed.ClassifierRequest;
import me.golemcore.bot.domain.resilience.autoproceed.ClassifierVerdict;
import me.golemcore.bot.domain.service.InternalTurnService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * Off by default — the operator must opt in via the Resilience dashboard.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutoProceedSystem implements AgentSystem {

    static final String SAFE_AFFIRMATION_PROMPT = "Proceed with the single non-destructive next step you just asked to continue.";

    private final AutoProceedClassifier classifier;
    private final InternalTurnService internalTurnService;
    private final RuntimeConfigService runtimeConfigService;

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
        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.RESILIENCE_AUTO_PROCEED_SCHEDULED))) {
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
        return incomingDepth < maxChainDepth;
    }

    @Override
    public AgentContext process(AgentContext context) {
        if (!shouldProcess(context)) {
            return context;
        }
        RuntimeConfig.AutoProceedConfig cfg = runtimeConfigService.getAutoProceedConfig();
        String modelTier = cfg.getModelTier();
        Duration timeout = Duration.ofSeconds(cfg.getTimeoutSeconds());
        int incomingDepth = readIncomingChainDepth(context);

        ClassifierRequest request = new ClassifierRequest(
                extractLastUserText(context),
                extractAssistantText(context),
                executedToolsSinceLastUserMessage(context));
        ClassifierVerdict verdict = classifier.classify(request, modelTier, timeout);

        if (!verdict.shouldAutoAffirm()) {
            log.debug("[AutoProceed] classifier declined to affirm (intent={}, reason={})",
                    verdict.intentType(), verdict.reason());
            return context;
        }
        if (verdict.riskLevel() != null && verdict.riskLevel().isHighRisk()) {
            log.debug("[AutoProceed] classifier marked rhetorical confirm high risk; skipping affirmation (reason={})",
                    verdict.reason());
            return context;
        }
        if (isInterruptRequested(context)) {
            log.debug("[AutoProceed] skipping affirmation dispatch: interrupt requested mid-classifier");
            return context;
        }

        boolean scheduled = internalTurnService.scheduleAutoProceedAffirmation(
                context, SAFE_AFFIRMATION_PROMPT, incomingDepth);
        if (scheduled) {
            context.setAttribute(ContextAttributes.RESILIENCE_AUTO_PROCEED_SCHEDULED, true);
            log.info("[AutoProceed] affirmation scheduled (sessionId={}, nextDepth={})",
                    context.getSession() != null ? context.getSession().getId() : "?",
                    incomingDepth + 1);
        }
        return context;
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
        Object raw = lastUser.getMetadata().get(ContextAttributes.RESILIENCE_AUTO_PROCEED_CHAIN_DEPTH);
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
        RuntimeConfig.AutoProceedConfig cfg = runtimeConfigService.getAutoProceedConfig();
        Integer max = cfg.getMaxChainDepth();
        return max != null && max >= 0 ? max : 2;
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
