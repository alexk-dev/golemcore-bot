package me.golemcore.bot.domain.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.Map;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.domain.model.TurnOutcome;
import org.junit.jupiter.api.Test;

class AutoRunOutcomeRecorderTest {

    @Test
    void shouldIgnoreNullAndNonAutoInputs() {
        AutoRunOutcomeRecorder recorder = new AutoRunOutcomeRecorder();
        Message inbound = Message.builder().metadata(new LinkedHashMap<>()).build();

        recorder.record(null, AgentContext.builder().build());
        recorder.record(inbound, null);
        recorder.record(inbound, AgentContext.builder().build());

        assertFalse(inbound.getMetadata().containsKey(ContextAttributes.AUTO_RUN_STATUS));
    }

    @Test
    void shouldRecordReflectionFailureWithSafeSummary() {
        AutoRunOutcomeRecorder recorder = new AutoRunOutcomeRecorder();
        Message inbound = Message.builder().metadata(new LinkedHashMap<>(Map.of(ContextAttributes.AUTO_MODE, true)))
                .build();
        AgentContext context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.AUTO_REFLECTION_ACTIVE, true);
        context.setOutgoingResponse(OutgoingResponse.textOnly("fallback text"));
        context.setTurnOutcome(TurnOutcome.builder().finishReason(FinishReason.ERROR)
                .routingOutcome(RoutingOutcome.builder().errorMessage("Routing failed").build()).build());

        recorder.record(inbound, context);

        assertEquals("REFLECTION_FAILED", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_STATUS));
        assertEquals("ERROR", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_FINISH_REASON));
        assertEquals("fallback text", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT));
        assertEquals("Routing failed", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY));
        assertEquals("routing failed", inbound.getMetadata().get(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT));
    }
}
