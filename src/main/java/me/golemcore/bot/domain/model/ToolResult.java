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
 * Result of tool execution containing success status, output text, optional
 * structured data, and error information. Tool results are sent back to the LLM
 * as tool messages in the conversation.
 */
@Data
@Builder
public class ToolResult {

    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName") // Lombok generates isSuccess()
    private boolean success;
    private String output;
    private Object data;
    private String error;

    /**
     * Creates a successful tool result with output text.
     */
    public static ToolResult success(String output) {
        return ToolResult.builder()
                .success(true)
                .output(output)
                .build();
    }

    /**
     * Creates a successful tool result with output text and structured data.
     */
    public static ToolResult success(String output, Object data) {
        return ToolResult.builder()
                .success(true)
                .output(output)
                .data(data)
                .build();
    }

    /**
     * Creates a failed tool result with an error message.
     */
    public static ToolResult failure(String error) {
        return ToolResult.builder()
                .success(false)
                .error(error)
                .build();
    }
}
