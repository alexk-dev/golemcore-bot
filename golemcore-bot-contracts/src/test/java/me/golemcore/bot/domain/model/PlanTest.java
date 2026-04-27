package me.golemcore.bot.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanTest {

    @Test
    void shouldTreatMissingStepsAsEmptyForCounters() {
        Plan plan = Plan.builder()
                .steps(null)
                .build();

        assertEquals(0, plan.getCompletedStepCount());
        assertEquals(0, plan.getFailedStepCount());
    }

    @Test
    void shouldCountCompletedAndFailedSteps() {
        Plan plan = Plan.builder()
                .steps(List.of(
                        PlanStep.builder().status(PlanStep.StepStatus.COMPLETED).build(),
                        PlanStep.builder().status(PlanStep.StepStatus.FAILED).build(),
                        PlanStep.builder().status(PlanStep.StepStatus.PENDING).build()))
                .build();

        assertEquals(1, plan.getCompletedStepCount());
        assertEquals(1, plan.getFailedStepCount());
    }
}
