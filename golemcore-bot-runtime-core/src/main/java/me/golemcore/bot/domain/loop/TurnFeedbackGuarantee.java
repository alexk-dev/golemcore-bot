package me.golemcore.bot.domain.loop;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.domain.model.TurnOutcome;

/**
 * Ensures a user-visible response is routed when the normal pipeline did not deliver one.
 */
@Slf4j
final class TurnFeedbackGuarantee {

    private final UnsentResponseDetector unsentResponseDetector;
    private final SafeErrorFeedbackRenderer safeErrorFeedbackRenderer;
    private final GenericFallbackRouter genericFallbackRouter;
    private final OptionalLlmErrorExplanationProvider optionalExplanationProvider;

    TurnFeedbackGuarantee(UnsentResponseDetector unsentResponseDetector,
            SafeErrorFeedbackRenderer safeErrorFeedbackRenderer, GenericFallbackRouter genericFallbackRouter,
            OptionalLlmErrorExplanationProvider optionalExplanationProvider) {
        this.unsentResponseDetector = unsentResponseDetector;
        this.safeErrorFeedbackRenderer = safeErrorFeedbackRenderer;
        this.genericFallbackRouter = genericFallbackRouter;
        this.optionalExplanationProvider = optionalExplanationProvider;
    }

    AgentContext ensure(AgentContext context) {
        TurnOutcome outcome = context.getTurnOutcome();
        RoutingOutcome routingOutcome = outcome != null ? outcome.getRoutingOutcome() : null;
        RoutingOutcome routingAttr = context.getAttribute(ContextAttributes.ROUTING_OUTCOME);
        if (unsentResponseDetector.delivered(context)) {
            return context;
        }

        if (isAutoModeContext(context)) {
            log.debug("[TurnFeedbackGuarantee] skipped: auto mode context");
            return context;
        }

        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.TURN_INTERNAL_RETRY_SCHEDULED))) {
            log.debug("[TurnFeedbackGuarantee] skipped: internal retry already scheduled");
            return context;
        }

        if (unsentResponseDetector.hasUnsentText(context)) {
            log.info("[TurnFeedbackGuarantee] routing unsent OutgoingResponse");
            return genericFallbackRouter.routeExisting(context);
        }

        String fallback = optionalExplanationProvider.explain(context)
                .map(safeErrorFeedbackRenderer::renderExplainedFallback)
                .orElseGet(safeErrorFeedbackRenderer::renderGenericFallback);
        OutgoingResponse outgoing = context.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        log.warn("[TurnFeedbackGuarantee] fallback triggered: routing safe feedback "
                + "(turnRoutingOutcome={}, attributeRoutingOutcome={}, outgoingResponse={}, llmErrorPresent={}, failures={})",
                describeRoutingOutcome(routingOutcome), describeRoutingOutcome(routingAttr),
                describeOutgoingResponse(outgoing), llmError != null && !llmError.isBlank(),
                context.getFailures().size());
        return genericFallbackRouter.route(context, fallback);
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
