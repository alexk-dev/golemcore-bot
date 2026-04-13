package me.golemcore.bot.port.outbound;

import java.util.Optional;
import me.golemcore.bot.domain.model.hive.HivePolicyBindingState;

public interface HivePolicyStatePort {

    Optional<HivePolicyBindingState> load();

    void save(HivePolicyBindingState state);

    void clear();
}
