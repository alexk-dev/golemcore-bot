package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceNamingSupportTest {

    @Test
    void shouldReturnFollowThroughTraceNameForInternalFollowThroughMessage() {
        Message message = Message.builder().channelType("telegram").chatId("chat-1")
                .metadata(Map.of(ContextAttributes.MESSAGE_INTERNAL_KIND,
                        ContextAttributes.MESSAGE_INTERNAL_KIND_FOLLOW_THROUGH_NUDGE))
                .build();

        assertEquals(TraceNamingSupport.RESILIENCE_FOLLOW_THROUGH_NUDGE, TraceNamingSupport.inboundMessage(message));
    }

    @Test
    void shouldReturnAutoProceedTraceNameForInternalAutoProceedMessage() {
        Message message = Message.builder().channelType("telegram").chatId("chat-1").metadata(
                Map.of(ContextAttributes.MESSAGE_INTERNAL_KIND, ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_PROCEED))
                .build();

        assertEquals(TraceNamingSupport.RESILIENCE_AUTO_PROCEED_AFFIRMATION,
                TraceNamingSupport.inboundMessage(message));
    }

    @Test
    void shouldReturnDelayedActionTraceNameForInternalDelayedActionMessage() {
        Message message = Message.builder().channelType("telegram").chatId("chat-1").metadata(
                Map.of(ContextAttributes.MESSAGE_INTERNAL_KIND, ContextAttributes.MESSAGE_INTERNAL_KIND_DELAYED_ACTION))
                .build();

        assertEquals(TraceNamingSupport.DELAYED_ACTION, TraceNamingSupport.inboundMessage(message));
    }

    @Test
    void shouldReturnScheduledTaskTraceNameForScheduledTaskSchedule() {
        ScheduleEntry schedule = ScheduleEntry.builder().type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-1").build();

        assertEquals("auto.schedule.scheduled_task", TraceNamingSupport.autoSchedule(schedule));
    }
}
