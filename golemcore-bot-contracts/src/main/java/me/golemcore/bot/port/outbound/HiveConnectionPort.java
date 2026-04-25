package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.hive.HiveStatusSnapshot;

/**
 * Hive connection control/query operations exposed to inbound adapters.
 */
public interface HiveConnectionPort {

    HiveStatusSnapshot getStatus();

    HiveStatusSnapshot join(String requestedJoinCode);

    HiveStatusSnapshot reconnect();

    HiveStatusSnapshot leave();
}
