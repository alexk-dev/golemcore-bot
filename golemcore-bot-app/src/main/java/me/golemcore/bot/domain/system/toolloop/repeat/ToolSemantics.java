package me.golemcore.bot.domain.system.toolloop.repeat;

import java.util.Set;

/**
 * Repeat-guard semantics for a tool call.
 */
// @formatter:off
record ToolSemantics(
        ToolUseCategory category,
        Set<ToolStateDomain> observedDomains,
        Set<ToolStateDomain> invalidatedDomains) {
}
// @formatter:on
