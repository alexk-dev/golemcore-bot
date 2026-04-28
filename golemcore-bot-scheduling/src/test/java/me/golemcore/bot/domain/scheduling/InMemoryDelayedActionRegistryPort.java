package me.golemcore.bot.domain.scheduling;

import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.port.outbound.DelayedActionRegistryPort;

public class InMemoryDelayedActionRegistryPort implements DelayedActionRegistryPort {

    private final List<DelayedSessionAction> actions = new ArrayList<>();

    @Override
    public List<DelayedSessionAction> loadActions() {
        return new ArrayList<>(actions);
    }

    @Override
    public void saveActions(List<DelayedSessionAction> updatedActions) {
        actions.clear();
        if (updatedActions != null) {
            actions.addAll(updatedActions);
        }
    }
}
