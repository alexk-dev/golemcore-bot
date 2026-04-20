package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;

/**
 * Outbound contract for obtaining the current SelfEvolving tactic-search
 * runtime status snapshot.
 */
public interface SelfEvolvingTacticSearchStatusPort {

    TacticSearchStatus getCurrentStatus();
}
