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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.ToolCallExecutionResult;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.system.toolloop.ToolCallExecutionServiceToolExecutorAdapter;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolLoopToolExecutorAdaptersTest {

    @Test
    void toolCallExecutionServiceAdapter_shouldUseToolCallIdsAndNames() {
        ToolCallExecutionService svc = mock(ToolCallExecutionService.class);
        ToolCallExecutionServiceToolExecutorAdapter adapter = new ToolCallExecutionServiceToolExecutorAdapter(svc);

        AgentContext ctx = AgentContext.builder().build();
        Message.ToolCall call = Message.ToolCall.builder().id("tc1").name("shell").build();

        ToolCallExecutionResult svcResult = new ToolCallExecutionResult(
                "tc1",
                "shell",
                ToolResult.success("ok"),
                "ok",
                null);
        when(svc.execute(ctx, call)).thenReturn(svcResult);

        ToolExecutionOutcome out = adapter.execute(ctx, call);
        assertEquals("tc1", out.toolCallId());
        assertEquals("shell", out.toolName());
        assertEquals("ok", out.toolResult().getOutput());
        assertEquals("ok", out.messageContent());
        assertFalse(out.synthetic());
    }
}
