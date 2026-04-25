package me.golemcore.bot.domain.model;

import java.util.Set;

/**
 * Stable tool identifiers used by prompt assembly and concrete tool adapters.
 */
public final class ToolNames {

    public static final String HIVE_LIFECYCLE_SIGNAL = "hive_lifecycle_signal";
    public static final String HIVE_GET_CURRENT_CONTEXT = "hive_get_current_context";
    public static final String HIVE_GET_CARD = "hive_get_card";
    public static final String HIVE_SEARCH_CARDS = "hive_search_cards";
    public static final String HIVE_POST_THREAD_MESSAGE = "hive_post_thread_message";
    public static final String HIVE_REQUEST_REVIEW = "hive_request_review";
    public static final String HIVE_CREATE_FOLLOWUP_CARD = "hive_create_followup_card";
    public static final Set<String> HIVE_SDLC_TOOLS = Set.of(
            HIVE_LIFECYCLE_SIGNAL,
            HIVE_GET_CURRENT_CONTEXT,
            HIVE_GET_CARD,
            HIVE_SEARCH_CARDS,
            HIVE_POST_THREAD_MESSAGE,
            HIVE_REQUEST_REVIEW,
            HIVE_CREATE_FOLLOWUP_CARD);
    public static final String SCHEDULE_SESSION_ACTION = "schedule_session_action";
    public static final String MEMORY = "memory";
    public static final String SHELL = "shell";
    public static final String FILESYSTEM = "filesystem";
    public static final String GOAL_MANAGEMENT = "goal_management";
    public static final String PLAN_EXIT = "plan_exit";

    private ToolNames() {
    }
}
