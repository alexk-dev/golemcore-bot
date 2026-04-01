package me.golemcore.bot.domain.model;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

/**
 * Constants for {@link AgentContext} attribute keys used to communicate between
 * systems and tools in the agent loop pipeline.
 */
public final class ContextAttributes {

    private ContextAttributes() {
    }

    /** Boolean — signal to stop the agent loop after current iteration. */
    /** LlmResponse — response from LLM execution. */
    public static final String LLM_RESPONSE = "llm.response";

    /** String — LLM error message. */
    public static final String LLM_ERROR = "llm.error";

    /** String — machine-readable LLM error classification code. */
    public static final String LLM_ERROR_CODE = "llm.error.code";

    /** String ? active request trace identifier. */
    public static final String TRACE_ID = "trace.id";

    /** String ? active span identifier. */
    public static final String TRACE_SPAN_ID = "trace.span.id";

    /** String ? parent span identifier for the active span. */
    public static final String TRACE_PARENT_SPAN_ID = "trace.parent.span.id";

    /** String ? root span kind for the current trace. */
    public static final String TRACE_ROOT_KIND = "trace.root.kind";

    /** String ? canonical trace/root span name. */
    public static final String TRACE_NAME = "trace.name";

    /** Boolean ? one or more trace payloads were evicted or truncated. */
    public static final String TRACE_TRUNCATED = "trace.truncated";

    /** List<Message.ToolCall> ? last tool calls requested by the LLM. */

    /** Boolean ? final answer is ready and the turn can be finalized/routed. */
    public static final String FINAL_ANSWER_READY = "llm.final.ready";

    /** OutgoingResponse ? response to route to the user (transport contract). */
    public static final String OUTGOING_RESPONSE = "outgoing.response";

    /** RoutingOutcome ? transport routing delivery result for the current turn. */
    public static final String ROUTING_OUTCOME = "routing.outcome";

    /** Duration ? latency of the last LLM call (best-effort). */
    public static final String LLM_LATENCY = "llm.latency";

    /** Boolean ? compatibility fallback flattened tool history for this turn. */
    public static final String LLM_COMPAT_FLATTEN_FALLBACK_USED = "llm.compat.flatten.fallback.used";

    /**
     * Map<String, ToolComponent> ? context-scoped tools available only in the
     * current turn.
     */
    public static final String CONTEXT_SCOPED_TOOLS = "context.scoped.tools";

    /** Boolean — tools were executed in this iteration. */

    /**
     * String — LLM model name that last generated tool calls in the session
     * (persisted in session metadata).
     */
    public static final String LLM_MODEL = "llm.model";

    /**
     * String — reasoning effort used for the current LLM call (e.g. "low",
     * "medium", "high").
     */
    public static final String LLM_REASONING = "llm.reasoning";

    /** String ? source that selected the current model tier for this turn. */
    public static final String MODEL_TIER_SOURCE = "model.tier.source";

    /** String ? resolved model id currently backing the active model tier. */
    public static final String MODEL_TIER_MODEL_ID = "model.tier.model.id";

    /**
     * String ? resolved reasoning level currently backing the active model tier.
     */
    public static final String MODEL_TIER_REASONING = "model.tier.reasoning";

    /** Boolean — plan mode is active for the current session. */
    public static final String PLAN_MODE_ACTIVE = "plan.mode.active";

    /** String — plan ID that needs user approval before execution. */
    public static final String PLAN_APPROVAL_NEEDED = "plan.approval.needed";

    /**
     * Boolean ? set when plan_set_content tool call was observed in LLM response.
     */
    public static final String PLAN_SET_CONTENT_REQUESTED = "plan.set_content.requested";

    /** String ? prompt suffix/extra context produced by RAG/context building. */
    public static final String RAG_CONTEXT = "rag.context";

    /** Map<String,Object> ? diagnostics for the selected memory pack. */
    public static final String MEMORY_PACK_DIAGNOSTICS = "memory.pack.diagnostics";

    /** Boolean ? input sanitization already performed for this context. */
    public static final String SANITIZATION_PERFORMED = "sanitization.performed";

    /** List<String> ? detected sanitization threats (best-effort). */
    public static final String SANITIZATION_THREATS = "sanitization.threats";

    /**
     * SkillTransitionRequest ? requested skill transition (from
     * SkillTransitionTool/SkillPipelineSystem).
     */

    /** Boolean ? loop iteration limit reached (AgentLoop safeguard). */
    public static final String ITERATION_LIMIT_REACHED = "iteration.limit.reached";

    /**
     * Boolean — tool loop stopped due to internal limit (LLM calls / tool
     * executions / deadline).
     */
    public static final String TOOL_LOOP_LIMIT_REACHED = "toolloop.limit.reached";

    /**
     * TurnLimitReason ? machine-readable reason why tool loop limit was reached.
     */
    public static final String TOOL_LOOP_LIMIT_REASON = "toolloop.limit.reason";

    /**
     * List<RuntimeEvent> — runtime execution events for the current turn.
     */
    public static final String RUNTIME_EVENTS = "runtime.events";

    /**
     * Boolean — stop tool execution between tool calls for current turn.
     */
    public static final String TURN_INTERRUPT_REQUESTED = "turn.interrupt.requested";

    /** String — queue kind for inbound message while a turn is running. */
    public static final String TURN_QUEUE_KIND = "turn.queue.kind";

    /**
     * String value for {@link #TURN_QUEUE_KIND}: internal retry after model
     * failure.
     */
    public static final String TURN_QUEUE_KIND_INTERNAL_RETRY = "internal_retry";

    /** String value for {@link #TURN_QUEUE_KIND}: prioritize as steering input. */
    public static final String TURN_QUEUE_KIND_STEERING = "steering";

    /** String value for {@link #TURN_QUEUE_KIND}: process as regular follow-up. */
    public static final String TURN_QUEUE_KIND_FOLLOW_UP = "follow_up";

    /** String value for {@link #TURN_QUEUE_KIND}: delayed internal wake-up. */
    public static final String TURN_QUEUE_KIND_DELAYED_ACTION = "delayed_action";

    /**
     * Boolean — current turn scheduled an internal retry instead of user feedback.
     */
    public static final String TURN_INTERNAL_RETRY_SCHEDULED = "turn.internal.retry.scheduled";

    /** Boolean ? message metadata flag for invisible internal messages. */
    public static final String MESSAGE_INTERNAL = "message.internal";

    /** String ? internal message kind stored in message metadata. */
    public static final String MESSAGE_INTERNAL_KIND = "message.internal.kind";

    /**
     * String value for {@link #MESSAGE_INTERNAL_KIND}: one-time auto-continue
     * retry.
     */
    public static final String MESSAGE_INTERNAL_KIND_AUTO_CONTINUE = "auto_continue";

    /**
     * String value for {@link #MESSAGE_INTERNAL_KIND}: delayed session wake-up or
     * follow-up.
     */
    public static final String MESSAGE_INTERNAL_KIND_DELAYED_ACTION = "delayed_action";

    /**
     * String value for {@link #MESSAGE_INTERNAL_KIND}: internal tool recovery
     * guidance.
     */
    public static final String MESSAGE_INTERNAL_KIND_TOOL_RECOVERY = "tool_recovery";

    /** Boolean ? current inbound message is an internal runtime-only message. */
    public static final String TURN_INPUT_INTERNAL = "turn.input.internal";

    /** WebSocketSession — reference to WebSocket session for streaming. */
    public static final String WEB_STREAM_SINK = "web.stream.sink";

    /**
     * String ? transport chat id used for outbound delivery (for example Telegram
     * chat id when logical session key differs).
     */
    public static final String TRANSPORT_CHAT_ID = "session.transport.chat.id";

    /** String ? logical conversation key for the current turn/session. */
    public static final String CONVERSATION_KEY = "session.conversation.key";

    /** String ? channel type from canonical session identity. */
    public static final String SESSION_IDENTITY_CHANNEL = "session.identity.channel";

    /** String ? conversation key from canonical session identity. */
    public static final String SESSION_IDENTITY_CONVERSATION = "session.identity.conversation";

    /** Boolean ? marks an internally generated autonomous turn. */
    public static final String AUTO_MODE = "auto.mode";

    /** String ? auto execution run kind (`GOAL_RUN` or `TASK_RUN`). */
    public static final String AUTO_RUN_KIND = "auto.run.kind";

    /** String ? unique auto execution run identifier. */
    public static final String AUTO_RUN_ID = "auto.run.id";

    /** String ? source schedule identifier for the current auto execution. */
    public static final String AUTO_SCHEDULE_ID = "auto.schedule.id";

    /** String ? active auto goal identifier for the turn. */
    public static final String AUTO_GOAL_ID = "auto.goal.id";

    /** String ? active auto task identifier for the turn. */
    public static final String AUTO_TASK_ID = "auto.task.id";

    /** String ? active skill name applied during the current turn or message. */
    public static final String ACTIVE_SKILL_NAME = "skill.active.name";

    /** String ? source of the active skill for the current turn. */
    public static final String ACTIVE_SKILL_SOURCE = "skill.active.source";

    /** Boolean ? current auto run is a reflection/recovery run. */
    public static final String AUTO_REFLECTION_ACTIVE = "auto.reflection.active";

    /** String ? reflection model tier configured from goal/task settings. */
    public static final String AUTO_REFLECTION_TIER = "auto.reflection.tier";

    /**
     * Boolean ? goal/task reflection tier should override active skill metadata.
     */
    public static final String AUTO_REFLECTION_TIER_PRIORITY = "auto.reflection.tier.priority";

    /** String ? resolved status of the completed auto run. */
    public static final String AUTO_RUN_STATUS = "auto.run.status";

    /** String ? finish reason recorded for the completed auto run. */
    public static final String AUTO_RUN_FINISH_REASON = "auto.run.finish.reason";

    /** String ? summary of the most important auto-run failure. */
    public static final String AUTO_RUN_FAILURE_SUMMARY = "auto.run.failure.summary";

    /** String ? normalized fingerprint of the most important auto-run failure. */
    public static final String AUTO_RUN_FAILURE_FINGERPRINT = "auto.run.failure.fingerprint";

    /** String ? assistant text produced by the completed auto run. */
    public static final String AUTO_RUN_ASSISTANT_TEXT = "auto.run.assistant.text";

    /** String ? active skill name used during the completed auto run. */
    public static final String AUTO_RUN_ACTIVE_SKILL = "auto.run.active.skill";

    /** String ? delayed action identifier for the current turn/message. */
    public static final String DELAYED_ACTION_ID = "delayed.action.id";

    /** String ? delayed action kind for the current turn/message. */
    public static final String DELAYED_ACTION_KIND = "delayed.action.kind";

    /** String ? scheduled run timestamp for the delayed action. */
    public static final String DELAYED_ACTION_RUN_AT = "delayed.action.run_at";

    /** String ? Hive card identifier bound to the current turn. */
    public static final String HIVE_CARD_ID = "hive.card.id";

    /** String ? Hive thread identifier bound to the current turn. */
    public static final String HIVE_THREAD_ID = "hive.thread.id";

    /** String ? Hive command identifier bound to the current turn. */
    public static final String HIVE_COMMAND_ID = "hive.command.id";

    /** String ? Hive run identifier bound to the current turn. */
    public static final String HIVE_RUN_ID = "hive.run.id";

    /** String ? Hive golem identifier for the active control-plane session. */
    public static final String HIVE_GOLEM_ID = "hive.golem.id";

    /** String ? SelfEvolving run identifier bound to the current turn. */
    public static final String SELF_EVOLVING_RUN_ID = "selfevolving.run.id";

    /**
     * String ? SelfEvolving artifact bundle identifier bound to the current turn.
     */
    public static final String SELF_EVOLVING_ARTIFACT_BUNDLE_ID = "selfevolving.artifact.bundle.id";

    /** Boolean ? whether post-run SelfEvolving analysis already completed. */
    public static final String SELF_EVOLVING_ANALYSIS_COMPLETED = "selfevolving.analysis.completed";

    /** TacticSearchQuery ? expanded tactic search query for the active turn. */
    public static final String SELF_EVOLVING_TACTIC_QUERY = "selfevolving.tactic.query";

    /**
     * List<TacticSearchResult> ? tactic-search candidates prepared for the turn.
     */
    public static final String SELF_EVOLVING_TACTIC_RESULTS = "selfevolving.tactic.results";

    /** TacticSearchResult ? selected tactic candidate for runtime guidance. */
    public static final String SELF_EVOLVING_TACTIC_SELECTION = "selfevolving.tactic.selection";

    /** TacticSearchResult ? tactic guidance carried across pipeline transitions. */
    public static final String SELF_EVOLVING_TACTIC_GUIDANCE = "selfevolving.tactic.guidance";

    /** Map<String,Object> ? latest structured compaction details. */
    public static final String COMPACTION_LAST_DETAILS = "compaction.last.details";

    /** List<Map<String,Object>> ? per-turn edited file stats for UI hints. */
    public static final String TURN_FILE_CHANGES = "turn.file.changes";

    /**
     * Boolean ? whether a live progress intent was already published for this turn.
     */
    public static final String TURN_PROGRESS_INTENT_PUBLISHED = "turn.progress.intent.published";

    /** List<ToolExecutionTrace> ? buffered tool executions awaiting summary. */
    public static final String TURN_PROGRESS_BUFFER = "turn.progress.buffer";

    /** Instant ? timestamp when the current progress batch started. */
    public static final String TURN_PROGRESS_BATCH_STARTED_AT = "turn.progress.batch.startedAt";

    /**
     * List<Map<String,Object>> ? assistant-facing attachments produced during this
     * turn.
     */
    public static final String TURN_OUTPUT_ATTACHMENTS = "turn.output.attachments";

}
