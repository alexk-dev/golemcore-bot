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

    /**
     * Boolean — voice response requested by VoiceResponseTool or incoming voice.
     */
    public static final String VOICE_REQUESTED = "voiceRequested";

    /** String — specific text to synthesize (from VoiceResponseTool). */
    public static final String VOICE_TEXT = "voiceText";

    /** Boolean — signal to stop the agent loop after current iteration. */
    public static final String LOOP_COMPLETE = "loop.complete";

    /** LlmResponse — response from LLM execution. */
    public static final String LLM_RESPONSE = "llm.response";

    /** String — LLM error message. */
    public static final String LLM_ERROR = "llm.error";

    /** Boolean — tools were executed in this iteration. */
    public static final String TOOLS_EXECUTED = "tools.executed";

    /** List&lt;Attachment&gt; — attachments pending delivery to channel. */
    public static final String PENDING_ATTACHMENTS = "pendingAttachments";

    /**
     * String — LLM model name that last generated tool calls in the session
     * (persisted in session metadata).
     */
    public static final String LLM_MODEL = "llm.model";
}
