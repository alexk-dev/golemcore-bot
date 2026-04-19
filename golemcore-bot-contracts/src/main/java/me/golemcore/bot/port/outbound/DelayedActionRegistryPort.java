package me.golemcore.bot.port.outbound;

import java.util.List;
import me.golemcore.bot.domain.model.DelayedSessionAction;

public interface DelayedActionRegistryPort {

    List<DelayedSessionAction> loadActions();

    void saveActions(List<DelayedSessionAction> actions);
}
