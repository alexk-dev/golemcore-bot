package me.golemcore.bot.domain.system.toolloop.repeat;

import java.time.Duration;
import java.util.Optional;

/**
 * Port for durable repeat-guard ledgers scoped to autonomous work items.
 */
public interface ToolUseLedgerStore {

    Optional<ToolUseLedger> load(AutonomyWorkKey key, Duration ttl);

    void save(AutonomyWorkKey key, ToolUseLedger ledger);

    static ToolUseLedgerStore noop() {
        return new NoopToolUseLedgerStore();
    }

    final class NoopToolUseLedgerStore implements ToolUseLedgerStore {

        private NoopToolUseLedgerStore() {
        }

        @Override
        public Optional<ToolUseLedger> load(AutonomyWorkKey key, Duration ttl) {
            return Optional.empty();
        }

        @Override
        public void save(AutonomyWorkKey key, ToolUseLedger ledger) {
            // no-op
        }
    }
}
