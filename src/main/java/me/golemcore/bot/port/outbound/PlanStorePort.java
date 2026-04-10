package me.golemcore.bot.port.outbound;

import java.util.List;
import me.golemcore.bot.domain.model.Plan;

/**
 * Loads and persists plan-mode state.
 */
public interface PlanStorePort {

    List<Plan> loadPlans();

    void savePlans(List<Plan> plans);
}
