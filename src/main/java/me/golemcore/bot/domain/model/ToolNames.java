package me.golemcore.bot.domain.model;

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
    public static final String SCHEDULE_SESSION_ACTION = "schedule_session_action";
    public static final String MEMORY = "memory";

    private ToolNames() {
    }
}
