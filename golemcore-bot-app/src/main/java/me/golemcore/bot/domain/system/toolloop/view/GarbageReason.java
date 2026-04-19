package me.golemcore.bot.domain.system.toolloop.view;

/**
 * Explicit reasons why request-time projection removed or compressed context.
 */
public enum GarbageReason {
    TRACE_OR_TELEMETRY, DUPLICATE_INSTRUCTION, STALE_SKILL_CONTEXT, COMPLETED_TASK, OLD_DIARY_ENTRY, RAW_TOOL_BLOB, LARGE_JSON_OR_HTML, BASE64_OR_BINARY, REPEATED_ERROR, LOW_RELEVANCE_MEMORY, SUPERSEDED_PLAN, PROVIDER_COMPAT_ONLY, BUDGET_EXCEEDED
}
