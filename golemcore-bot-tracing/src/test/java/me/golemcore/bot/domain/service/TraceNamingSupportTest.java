package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceNamingSupportTest {

    @Test
    void shouldReturnGoalTraceNameForNullOrGoalSchedule() {
        assertEquals("auto.schedule.goal", TraceNamingSupport.autoSchedule(null));
        assertEquals("auto.schedule.goal", TraceNamingSupport.autoSchedule(
                ScheduleEntry.builder().type(ScheduleEntry.ScheduleType.GOAL).targetId("goal-1").build()));
    }

    @Test
    void shouldReturnTaskTraceNameForTaskSchedule() {
        ScheduleEntry schedule = ScheduleEntry.builder().type(ScheduleEntry.ScheduleType.TASK).targetId("task-1")
                .build();

        assertEquals("auto.schedule.task", TraceNamingSupport.autoSchedule(schedule));
    }

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
    void shouldReturnAutoContinueTraceNameForInternalAutoContinueMessage() {
        Message message = Message.builder().channelType("telegram").chatId("chat-1").metadata(
                Map.of(ContextAttributes.MESSAGE_INTERNAL_KIND, ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_CONTINUE))
                .build();

        assertEquals(TraceNamingSupport.INTERNAL_AUTO_CONTINUE, TraceNamingSupport.inboundMessage(message));
    }

    @Test
    void shouldPreferExplicitTraceNameFromMetadata() {
        Message message = Message.builder().channelType("telegram").chatId("chat-1")
                .metadata(Map.of(ContextAttributes.TRACE_NAME, "custom.trace")).build();

        assertEquals("custom.trace", TraceNamingSupport.inboundMessage(message));
    }

    @Test
    void shouldFallbackToMessageWhenMessageOrChannelIsMissing() {
        assertEquals("message", TraceNamingSupport.inboundMessage(null));
        assertEquals("message", TraceNamingSupport.inboundMessage(Message.builder().channelType(" ").build()));
    }

    @Test
    void shouldBuildChannelMessageNameUsingLowercaseChannelType() {
        Message message = Message.builder().channelType("WebHook").chatId("chat-1").metadata(Map.of()).build();

        assertEquals("webhook.message", TraceNamingSupport.inboundMessage(message));
    }

    @Test
    void shouldReturnScheduledTaskTraceNameForScheduledTaskSchedule() {
        ScheduleEntry schedule = ScheduleEntry.builder().type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-1").build();

        assertEquals("auto.schedule.scheduled_task", TraceNamingSupport.autoSchedule(schedule));
    }
}
