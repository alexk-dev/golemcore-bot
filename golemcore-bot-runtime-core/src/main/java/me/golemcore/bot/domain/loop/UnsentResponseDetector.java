package me.golemcore.bot.domain.loop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.domain.model.TurnOutcome;

final class UnsentResponseDetector {

    boolean delivered(AgentContext context) {
        if (context == null) {
            return false;
        }
        TurnOutcome outcome = context.getTurnOutcome();
        RoutingOutcome routingOutcome = outcome != null ? outcome.getRoutingOutcome() : null;
        if (isDeliverySuccessful(routingOutcome)) {
            return true;
        }
        RoutingOutcome routingAttr = context.getAttribute(ContextAttributes.ROUTING_OUTCOME);
        return isDeliverySuccessful(routingAttr);
    }

    boolean hasUnsentText(AgentContext context) {
        OutgoingResponse outgoing = context != null ? context.getAttribute(ContextAttributes.OUTGOING_RESPONSE) : null;
        return outgoing != null && outgoing.getText() != null && !outgoing.getText().isBlank();
    }

    private boolean isDeliverySuccessful(RoutingOutcome routingOutcome) {
        if (routingOutcome == null) {
            return false;
        }
        return routingOutcome.isSentText() || routingOutcome.isSentVoice() || routingOutcome.getSentAttachments() > 0;
    }
}
