package me.golemcore.bot.domain.session;

import java.util.concurrent.atomic.AtomicLong;
import me.golemcore.bot.domain.autorun.AutoRunContextSupport;
import me.golemcore.bot.domain.model.Message;

final class SessionActivityTracker {

    private final AtomicLong realUserActivitySequence = new AtomicLong();

    Long recordIfRealUserActivity(Message inbound) {
        if (!isRealUserActivity(inbound)) {
            return null;
        }
        return Long.valueOf(realUserActivitySequence.incrementAndGet());
    }

    private boolean isRealUserActivity(Message inbound) {
        return inbound != null
                && !inbound.isInternalMessage()
                && !AutoRunContextSupport.isAutoMessage(inbound);
    }
}
