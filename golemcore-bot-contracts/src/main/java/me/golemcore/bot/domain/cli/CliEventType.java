package me.golemcore.bot.domain.cli;

/**
 * Event types emitted by CLI-oriented agent runs.
 */
public enum CliEventType {
    RUN_STARTED("run.started"), RUN_TITLE_UPDATED("run.title.updated"), ASSISTANT_DELTA(
            "assistant.delta"), ASSISTANT_MESSAGE_COMPLETED("assistant.message.completed"), PLAN_UPDATED(
                    "plan.updated"), CONTEXT_BUDGET_UPDATED("context.budget.updated"), CONTEXT_HYGIENE_REPORTED(
                            "context.hygiene.reported"), MEMORY_PACK_LOADED("memory.pack.loaded"), RAG_RESULTS_LOADED(
                                    "rag.results.loaded"), MODEL_SELECTED("model.selected"), TOOL_REQUESTED(
                                            "tool.requested"), PERMISSION_REQUESTED(
                                                    "tool.permission.requested"), PERMISSION_DECIDED(
                                                            "tool.permission.decided"), TOOL_STARTED(
                                                                    "tool.started"), TOOL_OUTPUT_DELTA(
                                                                            "tool.output.delta"), TOOL_COMPLETED(
                                                                                    "tool.completed"), PATCH_PROPOSED(
                                                                                            "patch.proposed"), PATCH_APPLIED(
                                                                                                    "patch.applied"), WORKSPACE_SNAPSHOT(
                                                                                                            "workspace.snapshot"), LSP_DIAGNOSTICS_UPDATED(
                                                                                                                    "lsp.diagnostics.updated"), TERMINAL_SESSION_STARTED(
                                                                                                                            "terminal.session.started"), RUN_CANCELLED(
                                                                                                                                    "run.cancelled"), RUN_COMPLETED(
                                                                                                                                            "run.completed"), RUN_FAILED(
                                                                                                                                                    "run.failed");

    private final String serializedValue;

    CliEventType(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    public String wireValue() {
        return serializedValue;
    }
}
