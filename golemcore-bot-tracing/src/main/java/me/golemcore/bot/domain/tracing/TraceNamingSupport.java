package me.golemcore.bot.domain.tracing;

import me.golemcore.bot.domain.autorun.AutoRunContextSupport;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.support.StringValueSupport;

import java.util.Locale;

/**
 * Canonical span/trace names for request roots.
 */
public final class TraceNamingSupport {

    public static final String WEBSOCKET_MESSAGE = "websocket.message";
    public static final String WEBHOOK_WAKE = "webhook.wake";
    public static final String WEBHOOK_AGENT = "webhook.agent";
    public static final String INTERNAL_AUTO_CONTINUE = "internal.auto_continue";
    public static final String DELAYED_ACTION = "delayed.action";
    public static final String RESILIENCE_FOLLOW_THROUGH_NUDGE = "resilience.follow_through.nudge";
    public static final String RESILIENCE_AUTO_PROCEED_AFFIRMATION = "resilience.auto_proceed.affirmation";

    private TraceNamingSupport() {
    }

    public static String autoSchedule(ScheduleEntry schedule) {
        if (schedule != null && schedule.getType() == ScheduleEntry.ScheduleType.TASK) {
            return "auto.schedule.task";
        }
        if (schedule != null && schedule.getType() == ScheduleEntry.ScheduleType.SCHEDULED_TASK) {
            return "auto.schedule.scheduled_task";
        }
        return "auto.schedule.goal";
    }

    public static String inboundMessage(Message message) {
        if (message == null) {
            return "message";
        }
        if (message.getMetadata() != null) {
            String explicit = TraceContextSupport.readTraceName(message.getMetadata());
            if (!StringValueSupport.isBlank(explicit)) {
                return explicit;
            }
            String internalKind = AutoRunContextSupport.readMetadataString(message.getMetadata(),
                    ContextAttributes.MESSAGE_INTERNAL_KIND);
            if (ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_CONTINUE.equals(internalKind)) {
                return INTERNAL_AUTO_CONTINUE;
            }
            if (ContextAttributes.MESSAGE_INTERNAL_KIND_DELAYED_ACTION.equals(internalKind)) {
                return DELAYED_ACTION;
            }
            if (ContextAttributes.MESSAGE_INTERNAL_KIND_FOLLOW_THROUGH_NUDGE.equals(internalKind)) {
                return RESILIENCE_FOLLOW_THROUGH_NUDGE;
            }
            if (ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_PROCEED.equals(internalKind)) {
                return RESILIENCE_AUTO_PROCEED_AFFIRMATION;
            }
        }
        String channelType = message.getChannelType();
        if (StringValueSupport.isBlank(channelType)) {
            return "message";
        }
        return channelType.toLowerCase(Locale.ROOT) + ".message";
    }
}
