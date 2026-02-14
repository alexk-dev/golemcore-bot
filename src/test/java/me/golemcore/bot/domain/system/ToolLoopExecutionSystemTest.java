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
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.system.toolloop.ToolLoopSystem;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ToolLoopExecutionSystemTest {

    @Test
    void shouldNotProcessWhenLlmErrorPresent() {
        ToolLoopSystem toolLoopSystem = mock(ToolLoopSystem.class);
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive()).thenReturn(false);

        ToolLoopExecutionSystem system = new ToolLoopExecutionSystem(toolLoopSystem, planService);

        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("telegram").chatId("1").build())
                .attributes(Map.of(ContextAttributes.LLM_ERROR, "boom"))
                .build();

        assertFalse(system.shouldProcess(context));
        verifyNoInteractions(toolLoopSystem);
    }

    @Test
    void processShouldInvokeToolLoopAndReturnSameContextInstance() {
        ToolLoopSystem toolLoopSystem = mock(ToolLoopSystem.class);
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive()).thenReturn(false);

        ToolLoopExecutionSystem system = new ToolLoopExecutionSystem(toolLoopSystem, planService);

        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("telegram").chatId("1").build())
                .build();

        assertTrue(system.shouldProcess(context));

        AgentContext result = system.process(context);
        assertSame(context, result);
        verify(toolLoopSystem, times(1)).processTurn(context);
    }
}
