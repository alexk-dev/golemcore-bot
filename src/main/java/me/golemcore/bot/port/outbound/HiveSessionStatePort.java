package me.golemcore.bot.port.outbound;

import java.util.Optional;
import me.golemcore.bot.domain.model.HiveSessionState;

/**
 * Persists Hive control-plane session state.
 */
public interface HiveSessionStatePort {

    Optional<HiveSessionState> load();

    void save(HiveSessionState sessionState);

    void clear();
}
