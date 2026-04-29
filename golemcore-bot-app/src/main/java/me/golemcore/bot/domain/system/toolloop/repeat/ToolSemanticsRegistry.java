package me.golemcore.bot.domain.system.toolloop.repeat;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.golemcore.bot.domain.model.ToolNames;

/**
 * Central registry for repeat-guard semantics of first-party tools.
 */
final class ToolSemanticsRegistry {

    private static final Set<String> READ_OPERATIONS = Set.of(
            "read_file", "read", "list", "list_files", "list_directory", "search", "status", "stat", "file_info");
    private static final Set<String> IDEMPOTENT_FILESYSTEM_MUTATIONS = Set.of(
            "write_file", "delete", "create_directory", "mkdir");
    private static final Set<String> NON_IDEMPOTENT_FILESYSTEM_MUTATIONS = Set.of("append");
    private static final Set<String> MEMORY_OBSERVE_OPERATIONS = Set.of(
            "memory_search", "memory_read", "memory_expand_section");
    private static final Set<String> MEMORY_MUTATE_OPERATIONS = Set.of(
            "memory_add", "memory_update", "memory_promote", "memory_forget");
    private static final Set<String> GOAL_OBSERVE_OPERATIONS = Set.of(
            "list_goals", "get_goal", "list_tasks", "get_task", "read_diary", "status");
    private static final Set<String> GOAL_MUTATE_OPERATIONS = Set.of(
            "create_goal",
            "update_goal",
            "create_task",
            "complete_task",
            "append_diary",
            "plan_tasks",
            "update_task_status",
            "write_diary",
            "complete_goal",
            "delete_goal",
            "delete_task",
            "clear_completed");
    private static final Set<String> SCHEDULE_OBSERVE_OPERATIONS = Set.of("list", "status");
    private static final Set<String> SCHEDULE_MUTATE_OPERATIONS = Set.of("create", "cancel", "run_now");
    private static final Set<String> SKILL_OBSERVE_OPERATIONS = Set.of("list_skills", "get_skill");
    private static final Set<String> SKILL_IDEMPOTENT_MUTATIONS = Set.of("create_skill", "update_skill");
    private static final Set<String> SKILL_NON_IDEMPOTENT_MUTATIONS = Set.of("delete_skill");
    private static final Set<String> FIRST_PARTY_WEB_OBSERVE_TOOLS = Set.of(
            "browse", "brave_search", "tavily_search", "firecrawl_scrape", "perplexity_ask");

    ToolSemantics semantics(String toolName, Map<String, Object> arguments) {
        String normalizedToolName = normalizeValue(toolName);
        if (ToolNames.PLAN_EXIT.equals(normalizedToolName)) {
            return control();
        }
        if ("skill_transition".equals(normalizedToolName) || "set_tier".equals(normalizedToolName)) {
            return mutate(ToolUseCategory.MUTATE_IDEMPOTENT, ToolStateDomain.SESSION_CONTROL);
        }
        if ("send_voice".equals(normalizedToolName)) {
            return mutate(ToolUseCategory.MUTATE_NON_IDEMPOTENT, ToolStateDomain.SESSION_CONTROL);
        }
        if (ToolNames.SHELL.equals(normalizedToolName)) {
            return observe(ToolUseCategory.EXECUTE_UNKNOWN, ToolStateDomain.WORKSPACE);
        }
        String normalizedOperation = normalizeValue(stringValue(arguments, "operation"));
        if ("plan_get".equals(normalizedToolName)) {
            return observe(ToolUseCategory.OBSERVE, ToolStateDomain.PLAN, true);
        }
        if ("plan_set_content".equals(normalizedToolName)) {
            return mutate(ToolUseCategory.MUTATE_IDEMPOTENT, ToolStateDomain.PLAN);
        }
        if ("skill_management".equals(normalizedToolName)) {
            return skillSemantics(normalizedOperation);
        }
        if (ToolNames.MEMORY.equals(normalizedToolName)) {
            return memorySemantics(normalizedOperation);
        }
        if (ToolNames.GOAL_MANAGEMENT.equals(normalizedToolName)) {
            return goalSemantics(normalizedOperation);
        }
        if (ToolNames.SCHEDULE_SESSION_ACTION.equals(normalizedToolName)) {
            return scheduleSemantics(normalizedOperation);
        }
        if (isHiveObserveTool(normalizedToolName)) {
            return observe(ToolUseCategory.OBSERVE, ToolStateDomain.HIVE_REMOTE);
        }
        if (isHiveMutationTool(normalizedToolName)) {
            return mutate(ToolUseCategory.MUTATE_IDEMPOTENT, ToolStateDomain.HIVE_REMOTE);
        }
        if (ToolNames.FILESYSTEM.equals(normalizedToolName)) {
            return filesystemSemantics(normalizedOperation);
        }
        if (FIRST_PARTY_WEB_OBSERVE_TOOLS.contains(normalizedToolName)) {
            return observe(ToolUseCategory.OBSERVE, ToolStateDomain.WEB_REMOTE, false);
        }
        if ("weather".equals(normalizedToolName)) {
            return observe(ToolUseCategory.OBSERVE, ToolStateDomain.WEATHER, false);
        }
        if ("datetime".equals(normalizedToolName)) {
            return observe(ToolUseCategory.OBSERVE, ToolStateDomain.TIME, false);
        }
        if ("imap".equals(normalizedToolName)) {
            return observe(ToolUseCategory.OBSERVE, ToolStateDomain.MAIL, false);
        }
        if ("smtp".equals(normalizedToolName)) {
            return mutate(ToolUseCategory.MUTATE_NON_IDEMPOTENT, ToolStateDomain.MAIL);
        }

        String combined = normalizedToolName + "." + normalizedOperation;
        if (combined.contains("status") || combined.contains("poll")) {
            return observe(ToolUseCategory.POLL, ToolStateDomain.UNKNOWN);
        }
        if (READ_OPERATIONS.contains(normalizedOperation) || normalizedToolName.contains("read")
                || normalizedToolName.contains("list") || normalizedToolName.contains("search")) {
            return observe(ToolUseCategory.OBSERVE, ToolStateDomain.UNKNOWN, false);
        }
        return observe(ToolUseCategory.EXECUTE_UNKNOWN, ToolStateDomain.UNKNOWN);
    }

    private ToolSemantics filesystemSemantics(String normalizedOperation) {
        if (READ_OPERATIONS.contains(normalizedOperation)) {
            return observe(ToolUseCategory.OBSERVE, ToolStateDomain.WORKSPACE, true);
        }
        if (IDEMPOTENT_FILESYSTEM_MUTATIONS.contains(normalizedOperation)) {
            return mutate(ToolUseCategory.MUTATE_IDEMPOTENT, ToolStateDomain.WORKSPACE);
        }
        if (NON_IDEMPOTENT_FILESYSTEM_MUTATIONS.contains(normalizedOperation)) {
            return mutate(ToolUseCategory.MUTATE_NON_IDEMPOTENT, ToolStateDomain.WORKSPACE);
        }
        return observe(ToolUseCategory.EXECUTE_UNKNOWN, ToolStateDomain.WORKSPACE);
    }

    private ToolSemantics memorySemantics(String normalizedOperation) {
        if (MEMORY_OBSERVE_OPERATIONS.contains(normalizedOperation)) {
            return observe(ToolUseCategory.OBSERVE, ToolStateDomain.MEMORY, true);
        }
        if (MEMORY_MUTATE_OPERATIONS.contains(normalizedOperation)) {
            return mutate(ToolUseCategory.MUTATE_IDEMPOTENT, ToolStateDomain.MEMORY);
        }
        return observe(ToolUseCategory.EXECUTE_UNKNOWN, ToolStateDomain.MEMORY);
    }

    private ToolSemantics goalSemantics(String normalizedOperation) {
        if (GOAL_OBSERVE_OPERATIONS.contains(normalizedOperation)) {
            return observe(ToolUseCategory.OBSERVE, ToolStateDomain.AUTONOMY_PROGRESS, true);
        }
        if (GOAL_MUTATE_OPERATIONS.contains(normalizedOperation)) {
            return mutate(ToolUseCategory.MUTATE_IDEMPOTENT, ToolStateDomain.AUTONOMY_PROGRESS);
        }
        return observe(ToolUseCategory.EXECUTE_UNKNOWN, ToolStateDomain.AUTONOMY_PROGRESS);
    }

    private ToolSemantics scheduleSemantics(String normalizedOperation) {
        if (SCHEDULE_OBSERVE_OPERATIONS.contains(normalizedOperation)) {
            return observe(ToolUseCategory.OBSERVE, ToolStateDomain.SCHEDULING, true);
        }
        if (SCHEDULE_MUTATE_OPERATIONS.contains(normalizedOperation)) {
            return mutate(ToolUseCategory.MUTATE_IDEMPOTENT, ToolStateDomain.SCHEDULING);
        }
        return observe(ToolUseCategory.EXECUTE_UNKNOWN, ToolStateDomain.SCHEDULING);
    }

    private ToolSemantics skillSemantics(String normalizedOperation) {
        if (SKILL_OBSERVE_OPERATIONS.contains(normalizedOperation)) {
            return observe(ToolUseCategory.OBSERVE, ToolStateDomain.SKILLS, true);
        }
        if (SKILL_IDEMPOTENT_MUTATIONS.contains(normalizedOperation)) {
            return mutate(ToolUseCategory.MUTATE_IDEMPOTENT, ToolStateDomain.SKILLS);
        }
        if (SKILL_NON_IDEMPOTENT_MUTATIONS.contains(normalizedOperation)) {
            return mutate(ToolUseCategory.MUTATE_NON_IDEMPOTENT, ToolStateDomain.SKILLS);
        }
        return observe(ToolUseCategory.EXECUTE_UNKNOWN, ToolStateDomain.SKILLS);
    }

    private boolean isHiveObserveTool(String toolName) {
        return ToolNames.HIVE_GET_CURRENT_CONTEXT.equals(toolName)
                || ToolNames.HIVE_GET_CARD.equals(toolName)
                || ToolNames.HIVE_SEARCH_CARDS.equals(toolName);
    }

    private boolean isHiveMutationTool(String toolName) {
        return ToolNames.HIVE_LIFECYCLE_SIGNAL.equals(toolName)
                || ToolNames.HIVE_POST_THREAD_MESSAGE.equals(toolName)
                || ToolNames.HIVE_REQUEST_REVIEW.equals(toolName)
                || ToolNames.HIVE_CREATE_FOLLOWUP_CARD.equals(toolName);
    }

    private ToolSemantics observe(ToolUseCategory category, ToolStateDomain domain) {
        return observe(category, domain, false);
    }

    private ToolSemantics observe(
            ToolUseCategory category,
            ToolStateDomain domain,
            boolean outputDigestChangeResetsRepeatCount) {
        return new ToolSemantics(category, Set.of(domain), Set.of(), outputDigestChangeResetsRepeatCount);
    }

    private ToolSemantics mutate(ToolUseCategory category, ToolStateDomain domain) {
        return new ToolSemantics(category, Set.of(domain), Set.of(domain), false);
    }

    private ToolSemantics control() {
        return new ToolSemantics(ToolUseCategory.CONTROL, Set.of(), Set.of(), false);
    }

    private String stringValue(Map<String, Object> arguments, String key) {
        if (arguments == null || key == null) {
            return "";
        }
        Object value = arguments.get(key);
        return value instanceof String string ? string : "";
    }

    private String normalizeValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
