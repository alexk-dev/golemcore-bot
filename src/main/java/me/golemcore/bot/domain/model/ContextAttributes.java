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

    /** List<Message.ToolCall> ? last tool calls requested by the LLM. */

    /** Boolean ? final answer is ready and the turn can be finalized/routed. */
    public static final String FINAL_ANSWER_READY = "llm.final.ready";

    /** OutgoingResponse ? response to route to the user (transport contract). */
    public static final String OUTGOING_RESPONSE = "outgoing.response";

    /** Duration ? latency of the last LLM call (best-effort). */
    public static final String LLM_LATENCY = "llm.latency";

    /** Boolean — tools were executed in this iteration. */

    /**
     * String — LLM model name that last generated tool calls in the session
     * (persisted in session metadata).
     */
    public static final String LLM_MODEL = "llm.model";

    /** Boolean — plan mode is active for the current session. */
    public static final String PLAN_MODE_ACTIVE = "plan.mode.active";

    /** String — plan ID that needs user approval before execution. */
    public static final String PLAN_APPROVAL_NEEDED = "plan.approval.needed";

    /** Boolean ? set when plan_finalize tool call was observed in LLM response. */
    public static final String PLAN_FINALIZE_REQUESTED = "plan.finalize.requested";

    /** String ? prompt suffix/extra context produced by RAG/context building. */
    public static final String RAG_CONTEXT = "rag.context";

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

}
