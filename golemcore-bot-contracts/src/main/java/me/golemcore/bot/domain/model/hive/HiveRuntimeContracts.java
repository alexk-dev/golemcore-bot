package me.golemcore.bot.domain.model.hive;

import java.util.List;

/**
 * Stable Hive runtime constants shared between the app and Hive capability
 * modules.
 */
public final class HiveRuntimeContracts {

    public static final String USER_INTERRUPT_REASON = "user_interrupt";
    public static final String CANCELLED_BY_CONTROL_COMMAND_MESSAGE = "Cancelled by Hive control command";

    public static final String CONTROL_EVENT_TYPE_COMMAND = "command";
    public static final String CONTROL_EVENT_TYPE_STOP = "command.stop";
    public static final String CONTROL_EVENT_TYPE_CANCEL = "command.cancel";
    public static final String CONTROL_EVENT_TYPE_INSPECTION_REQUEST = "inspection.request";
    public static final String CONTROL_EVENT_TYPE_POLICY_SYNC_REQUESTED = "policy.sync_requested";
    public static final String EVENT_TYPE_INSPECTION_RESPONSE = "inspection_response";
    public static final String EVENT_TYPE_RUNTIME_EVENT = "runtime_event";
    public static final String EVENT_TYPE_CARD_LIFECYCLE_SIGNAL = "card_lifecycle_signal";
    public static final String TURN_INTERRUPT_SOURCE_COMMAND_STOP = CONTROL_EVENT_TYPE_STOP;

    public static final String RUNTIME_EVENT_TYPE_COMMAND_ACKNOWLEDGED = "COMMAND_ACKNOWLEDGED";
    public static final String RUNTIME_EVENT_TYPE_THREAD_MESSAGE = "THREAD_MESSAGE";
    public static final String RUNTIME_EVENT_TYPE_USAGE_REPORTED = "USAGE_REPORTED";
    public static final String RUNTIME_EVENT_TYPE_RUN_STARTED = "RUN_STARTED";
    public static final String RUNTIME_EVENT_TYPE_RUN_PROGRESS = "RUN_PROGRESS";
    public static final String RUNTIME_EVENT_TYPE_RUN_COMPLETED = "RUN_COMPLETED";
    public static final String RUNTIME_EVENT_TYPE_RUN_CANCELLED = "RUN_CANCELLED";
    public static final String RUNTIME_EVENT_TYPE_RUN_FAILED = "RUN_FAILED";

    public static final String SIGNAL_TYPE_WORK_STARTED = "WORK_STARTED";
    public static final String SIGNAL_TYPE_PROGRESS_REPORTED = "PROGRESS_REPORTED";
    public static final String SIGNAL_TYPE_BLOCKER_RAISED = "BLOCKER_RAISED";
    public static final String SIGNAL_TYPE_BLOCKER_CLEARED = "BLOCKER_CLEARED";
    public static final String SIGNAL_TYPE_REVIEW_REQUESTED = "REVIEW_REQUESTED";
    public static final String SIGNAL_TYPE_WORK_COMPLETED = "WORK_COMPLETED";
    public static final String SIGNAL_TYPE_WORK_FAILED = "WORK_FAILED";
    public static final String SIGNAL_TYPE_WORK_CANCELLED = "WORK_CANCELLED";
    public static final String SIGNAL_TYPE_REVIEW_STARTED = "REVIEW_STARTED";
    public static final String SIGNAL_TYPE_REVIEW_APPROVED = "REVIEW_APPROVED";
    public static final String SIGNAL_TYPE_CHANGES_REQUESTED = "CHANGES_REQUESTED";

    public static final String EVENT_TYPE_SELF_EVOLVING_RUN_UPSERTED = "selfevolving.run.upserted";
    public static final String EVENT_TYPE_SELF_EVOLVING_CANDIDATE_UPSERTED = "selfevolving.candidate.upserted";
    public static final String EVENT_TYPE_SELF_EVOLVING_CAMPAIGN_UPSERTED = "selfevolving.campaign.upserted";
    public static final String EVENT_TYPE_SELF_EVOLVING_ARTIFACT_UPSERTED = "selfevolving.artifact.upserted";
    public static final String EVENT_TYPE_SELF_EVOLVING_ARTIFACT_NORMALIZED_REVISION_UPSERTED = "selfevolving.artifact.normalized-revision.upserted";
    public static final String EVENT_TYPE_SELF_EVOLVING_ARTIFACT_LINEAGE_UPSERTED = "selfevolving.artifact.lineage.upserted";
    public static final String EVENT_TYPE_SELF_EVOLVING_ARTIFACT_DIFF_UPSERTED = "selfevolving.artifact.diff.upserted";
    public static final String EVENT_TYPE_SELF_EVOLVING_ARTIFACT_EVIDENCE_UPSERTED = "selfevolving.artifact.evidence.upserted";
    public static final String EVENT_TYPE_SELF_EVOLVING_ARTIFACT_IMPACT_UPSERTED = "selfevolving.artifact.impact.upserted";
    public static final String EVENT_TYPE_SELF_EVOLVING_TACTIC_UPSERTED = "selfevolving.tactic.upserted";
    public static final String EVENT_TYPE_SELF_EVOLVING_TACTIC_SEARCH_STATUS_UPSERTED = "selfevolving.tactic.search-status.upserted";

    public static final List<String> SUPPORTED_LIFECYCLE_SIGNAL_TYPES = List.of(
            SIGNAL_TYPE_WORK_STARTED,
            SIGNAL_TYPE_PROGRESS_REPORTED,
            SIGNAL_TYPE_BLOCKER_RAISED,
            SIGNAL_TYPE_BLOCKER_CLEARED,
            SIGNAL_TYPE_REVIEW_REQUESTED,
            SIGNAL_TYPE_WORK_COMPLETED,
            SIGNAL_TYPE_WORK_FAILED,
            SIGNAL_TYPE_WORK_CANCELLED,
            SIGNAL_TYPE_REVIEW_STARTED,
            SIGNAL_TYPE_REVIEW_APPROVED,
            SIGNAL_TYPE_CHANGES_REQUESTED);

    private HiveRuntimeContracts() {
    }
}
