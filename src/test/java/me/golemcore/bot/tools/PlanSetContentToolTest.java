package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.PlanService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanSetContentToolTest {

    @Test
    void shouldDenyOutsidePlanWork() {
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive()).thenReturn(false);

        PlanSetContentTool tool = new PlanSetContentTool(planService);
        ToolResult result = tool.execute(Map.of("plan_markdown", "# plan")).join();

        assertEquals(false, result.isSuccess());
        assertEquals(ToolFailureKind.POLICY_DENIED, result.getFailureKind());
    }

    @Test
    void shouldSucceedInsidePlanWork() {
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive()).thenReturn(true);

        PlanSetContentTool tool = new PlanSetContentTool(planService);
        ToolResult result = tool.execute(Map.of("plan_markdown", "# plan")).join();

        assertEquals(true, result.isSuccess());
    }
}
