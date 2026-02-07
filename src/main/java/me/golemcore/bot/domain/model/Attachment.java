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
import lombok.Data;

/**
 * Represents a binary attachment (image or document) to be sent to the user.
 * Attachments are transient and stored in AgentContext.attributes, never
 * persisted or sent to LLM.
 */
@Data
@Builder
public class Attachment {

    public enum Type {
        IMAGE, DOCUMENT
    }

    private Type type;
    private byte[] data;
    private String filename;
    private String mimeType;
    private String caption;
}
