package me.golemcore.bot.port.outbound;

import java.util.Optional;
import me.golemcore.bot.domain.model.policy.ManagedPolicyBindingState;

public interface ManagedPolicyStatePort {

    Optional<ManagedPolicyBindingState> load();

    void save(ManagedPolicyBindingState state);

    void clear();
}
