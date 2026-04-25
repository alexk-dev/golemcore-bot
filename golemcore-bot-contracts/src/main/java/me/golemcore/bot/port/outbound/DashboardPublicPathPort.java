package me.golemcore.bot.port.outbound;

import java.util.List;

/**
 * Contributes additional dashboard API path patterns that must stay public
 * without dashboard JWT authentication.
 */
public interface DashboardPublicPathPort {

    List<String> getPublicPathPatterns();
}
