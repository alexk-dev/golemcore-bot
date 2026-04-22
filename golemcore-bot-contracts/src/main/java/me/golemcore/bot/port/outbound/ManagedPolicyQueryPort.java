package me.golemcore.bot.port.outbound;

import java.util.Optional;
import me.golemcore.bot.domain.model.policy.ManagedPolicyBindingState;

/**
 * Query-side access to the current managed policy binding state.
 */
public interface ManagedPolicyQueryPort {

    Optional<ManagedPolicyBindingState> findBindingState();

    default boolean hasActiveBinding() {
        return findBindingState().map(ManagedPolicyBindingState::hasActiveBinding).orElse(false);
    }
}
