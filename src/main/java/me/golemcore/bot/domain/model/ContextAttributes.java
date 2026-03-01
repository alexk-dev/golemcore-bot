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

    /** String ? auto execution run kind (`GOAL_RUN` or `TASK_RUN`). */
    public static final String AUTO_RUN_KIND = "auto.run.kind";

    /** String ? unique auto execution run identifier. */
    public static final String AUTO_RUN_ID = "auto.run.id";

    /** String ? active auto goal identifier for the turn. */
    public static final String AUTO_GOAL_ID = "auto.goal.id";

    /** String ? active auto task identifier for the turn. */
    public static final String AUTO_TASK_ID = "auto.task.id";

}
