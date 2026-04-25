package me.golemcore.bot.domain.system;

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

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionOutcomeTest {

    @Test
    void synthetic_shouldCreateFailureResultAndMarkSynthetic() {
        Message.ToolCall call = Message.ToolCall.builder().id("tc1").name("shell").build();

        ToolExecutionOutcome out = ToolExecutionOutcome.synthetic(call, "reason");

        assertEquals("tc1", out.toolCallId());
        assertEquals("shell", out.toolName());
        assertTrue(out.synthetic());
        assertEquals("reason", out.messageContent());
        assertNotNull(out.toolResult());
        assertTrue(!out.toolResult().isSuccess());
        assertEquals(ToolFailureKind.EXECUTION_FAILED, out.toolResult().getFailureKind());
    }

    @Test
    void syntheticWithKind_shouldPreserveKind() {
        Message.ToolCall call = Message.ToolCall.builder().id("tc1").name("shell").build();

        ToolExecutionOutcome out = ToolExecutionOutcome.synthetic(call, ToolFailureKind.POLICY_DENIED, "denied");

        assertTrue(!out.toolResult().isSuccess());
        assertEquals(ToolFailureKind.POLICY_DENIED, out.toolResult().getFailureKind());
        assertEquals("denied", out.messageContent());
    }
}
