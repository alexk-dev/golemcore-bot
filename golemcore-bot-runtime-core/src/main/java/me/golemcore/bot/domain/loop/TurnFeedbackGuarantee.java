package me.golemcore.bot.domain.loop;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.LlmPort;

/**
 * Ensures a user-visible response is routed when the normal pipeline did not deliver one.
 */
@Slf4j
final class TurnFeedbackGuarantee {

    private final UserPreferencesService preferencesService;
    private final RuntimeConfigService runtimeConfigService;
    private final LlmPort llmPort;
    private final Clock clock;
    private final AgentPipelineRunner pipelineRunner;

    TurnFeedbackGuarantee(UserPreferencesService preferencesService, RuntimeConfigService runtimeConfigService,
            LlmPort llmPort, Clock clock, AgentPipelineRunner pipelineRunner) {
        this.preferencesService = preferencesService;
        this.runtimeConfigService = runtimeConfigService;
        this.llmPort = llmPort;
        this.clock = clock;
        this.pipelineRunner = pipelineRunner;
    }

    AgentContext ensure(AgentContext context) {
        TurnOutcome outcome = context.getTurnOutcome();
        RoutingOutcome routingOutcome = outcome != null ? outcome.getRoutingOutcome() : null;
        if (isDeliverySuccessful(routingOutcome)) {
            return context;
        }

        RoutingOutcome routingAttr = context.getAttribute(ContextAttributes.ROUTING_OUTCOME);
        if (isDeliverySuccessful(routingAttr)) {
            return context;
        }

        if (isAutoModeContext(context)) {
            log.debug("[AgentLoop] Feedback guarantee skipped: auto mode context");
            return context;
        }

        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.TURN_INTERNAL_RETRY_SCHEDULED))) {
            log.debug("[AgentLoop] Feedback guarantee skipped: internal retry already scheduled");
            return context;
        }

        AgentContext routed = tryUnsentLlmResponse(context);
        if (routed != null) {
            return routed;
        }

        routed = tryErrorFeedback(context);
        if (routed != null) {
            return routed;
        }

        String generic = preferencesService.getMessage("system.error.generic.feedback");
        OutgoingResponse outgoing = context.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        log.warn("[AgentLoop] Feedback guarantee fallback triggered: routing generic feedback "
                + "(turnRoutingOutcome={}, attributeRoutingOutcome={}, outgoingResponse={}, llmErrorPresent={}, failures={})",
                describeRoutingOutcome(routingOutcome), describeRoutingOutcome(routingAttr),
                describeOutgoingResponse(outgoing), llmError != null && !llmError.isBlank(),
                context.getFailures().size());
        return pipelineRunner.routeSyntheticAssistantResponse(context, generic);
    }

    private boolean isDeliverySuccessful(RoutingOutcome routingOutcome) {
        if (routingOutcome == null) {
            return false;
        }
        if (routingOutcome.isSentText()) {
            return true;
        }
        if (routingOutcome.isSentVoice()) {
            return true;
        }
        return routingOutcome.getSentAttachments() > 0;
    }

    private AgentContext tryUnsentLlmResponse(AgentContext context) {
        OutgoingResponse outgoing = context.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        if (outgoing != null && outgoing.getText() != null && !outgoing.getText().isBlank()) {
            log.info("[AgentLoop] Feedback guarantee: routing unsent OutgoingResponse");
            return pipelineRunner.routeResponse(context);
        }
        return null;
    }

    private AgentContext tryErrorFeedback(AgentContext context) {
        List<String> errors = collectErrors(context);
        if (errors.isEmpty() || !llmPort.isAvailable()) {
            return null;
        }

        String interpretation = tryInterpretErrors(context, errors);
        if (interpretation != null) {
            String message = preferencesService.getMessage("system.error.feedback", interpretation);
            log.info("[AgentLoop] Feedback guarantee: routing interpreted error");
            return pipelineRunner.routeSyntheticAssistantResponse(context, message);
        }

        return null;
    }

    private List<String> collectErrors(AgentContext context) {
        List<String> errors = new ArrayList<>();
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        if (llmError != null) {
            errors.add(llmError);
        }
        for (FailureEvent failure : context.getFailures()) {
            if (failure.message() != null) {
                errors.add(failure.message());
            }
        }
        TurnOutcome outcome = context.getTurnOutcome();
        if (outcome != null && outcome.getRoutingOutcome() != null
                && outcome.getRoutingOutcome().getErrorMessage() != null) {
            errors.add(outcome.getRoutingOutcome().getErrorMessage());
        }
        return errors;
    }

    private String tryInterpretErrors(AgentContext context, List<String> errors) {
        try {
            String errorSummary = String.join("\n", errors);
            LlmRequest request = LlmRequest.builder().model(runtimeConfigService.getRoutingModel())
                    .reasoningEffort(runtimeConfigService.getRoutingModelReasoning())
                    .systemPrompt(
                            "You are a helpful assistant. Explain the following error in 1-2 sentences for the user.")
                    .messages(List.of(
                            Message.builder().role("user").content(errorSummary).timestamp(clock.instant()).build()))
                    .sessionId(context.getSession() != null ? context.getSession().getId() : null)
                    .traceId(context.getTraceContext() != null ? context.getTraceContext().getTraceId() : null)
                    .traceSpanId(context.getTraceContext() != null ? context.getTraceContext().getSpanId() : null)
                    .traceParentSpanId(
                            context.getTraceContext() != null ? context.getTraceContext().getParentSpanId() : null)
                    .traceRootKind(context.getTraceContext() != null ? context.getTraceContext().getRootKind() : null)
                    .build();
            LlmResponse response = llmPort.chat(request).get(10, TimeUnit.SECONDS);
            if (response != null && response.getContent() != null && !response.getContent().isBlank()) {
                return response.getContent();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[AgentLoop] LLM error interpretation interrupted: {}", e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            log.debug("[AgentLoop] LLM error interpretation failed: {}", e.getMessage());
        }
        return null;
    }

    private boolean isAutoModeContext(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get(ContextAttributes.AUTO_MODE));
    }

    private String describeRoutingOutcome(RoutingOutcome routingOutcome) {
        if (routingOutcome == null) {
            return "<null>";
        }
        return String.format("attempted=%s,sentText=%s,sentVoice=%s,sentAttachments=%d,error=%s",
                routingOutcome.isAttempted(), routingOutcome.isSentText(), routingOutcome.isSentVoice(),
                routingOutcome.getSentAttachments(), truncate(routingOutcome.getErrorMessage(), 160));
    }

    private String describeOutgoingResponse(OutgoingResponse outgoing) {
        if (outgoing == null) {
            return "<null>";
        }

        String text = outgoing.getText();
        int textLength = text != null ? text.length() : 0;
        int attachmentCount = outgoing.getAttachments() != null ? outgoing.getAttachments().size() : 0;
        return String.format("textLength=%d,voiceRequested=%s,voiceTextPresent=%s,attachments=%d", textLength,
                outgoing.isVoiceRequested(), outgoing.getVoiceText() != null, attachmentCount);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "<null>";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
