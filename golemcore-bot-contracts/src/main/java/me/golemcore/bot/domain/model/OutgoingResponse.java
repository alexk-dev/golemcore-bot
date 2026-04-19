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

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A transport-oriented response produced by domain systems and consumed by
 * {@link me.golemcore.bot.domain.system.ResponseRoutingSystem}.
 * <p>
 * This object intentionally separates "what to send" from the internal
 * {@link LlmResponse} contract.
 */
@Value
@Builder
public class OutgoingResponse {

    /** Plain text to deliver to the user. */
    String text;

    /**
     * Whether the system should attempt to send a voice response (TTS).
     * <p>
     * This is a transport-level hint produced by upstream domain systems.
     */
    @Builder.Default
    boolean voiceRequested = false;

    /**
     * Optional explicit text to speak. If blank, routing may fall back to
     * {@link #text}.
     */
    String voiceText;

    /** Attachments to send after the main response (photos/documents). */
    @Singular
    List<Attachment> attachments;

    /** Protocol-level metadata hints (model, tier, token usage, latency). */
    @Builder.Default
    Map<String, Object> hints = new LinkedHashMap<>();

    /**
     * If true, response routing should not append a synthetic assistant message to
     * raw history (raw history ownership belongs to domain executors).
     */
    @Builder.Default
    boolean skipAssistantHistory = true;

    public static OutgoingResponse textOnly(String content) {
        return OutgoingResponse.builder().text(content).build();
    }

    public static OutgoingResponse voiceOnly(String voiceText) {
        return OutgoingResponse.builder().voiceRequested(true).voiceText(voiceText).text(null).build();
    }

}
