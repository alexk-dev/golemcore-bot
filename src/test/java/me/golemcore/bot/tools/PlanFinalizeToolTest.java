package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.PlanService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanFinalizeToolTest {

    @Test
    void shouldDenyWhenPlanModeInactive() {
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive()).thenReturn(false);

        PlanFinalizeTool tool = new PlanFinalizeTool(planService);

        ToolResult result = tool.execute(Map.of()).join();

        assertFalse(result.isSuccess());
        assertEquals(ToolFailureKind.POLICY_DENIED, result.getFailureKind());
    }

    @Test
    void shouldSucceedWhenPlanModeActive() {
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive()).thenReturn(true);

        PlanFinalizeTool tool = new PlanFinalizeTool(planService);

        ToolResult result = tool.execute(Map.of("summary", "ok")).join();

        assertTrue(result.isSuccess());
        assertEquals("[Plan finalized]", result.getOutput());
    }
}
