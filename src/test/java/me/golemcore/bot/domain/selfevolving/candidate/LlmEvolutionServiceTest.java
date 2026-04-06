package me.golemcore.bot.domain.selfevolving.candidate;

import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmEvolutionServiceTest {

    private LlmEvolutionService llmEvolutionService;

    @BeforeEach
    void setUp() {
        llmEvolutionService = new LlmEvolutionService();
    }

    @Test
    void shouldBuildConcreteFixProposalFromFailedToolRun() {
        EvolutionProposal proposal = llmEvolutionService.propose(
                RunRecord.builder()
                        .id("run-fix")
                        .golemId("golem-1")
                        .build(),
                RunVerdict.builder()
                        .outcomeStatus("FAILED")
                        .outcomeSummary("Command failed with exit code 127")
                        .processFindings(List.of("tool_error:tool.exec"))
                        .evidenceRefs(List.of(VerdictEvidenceRef.builder()
                                .traceId("trace-fix")
                                .spanId("tool-1")
                                .outputFragment("bash: rg: command not found")
                                .build()))
                        .build());

        assertNotNull(proposal);
        assertEquals("high", proposal.getRiskLevel());
        assertTrue(proposal.getSummary().contains("tool usage policy"));
        assertTrue(proposal.getBehaviorInstructions().contains("exit code 127"));
        assertTrue(proposal.getToolInstructions().contains("command -v"));
        assertTrue(proposal.getExpectedOutcome().contains("Reduce"));
    }

    @Test
    void shouldBuildReusableDeriveProposalFromSuccessfulRun() {
        EvolutionProposal proposal = llmEvolutionService.propose(
                RunRecord.builder()
                        .id("run-derive")
                        .golemId("golem-1")
                        .build(),
                RunVerdict.builder()
                        .outcomeStatus("COMPLETED")
                        .processStatus("CLEAN")
                        .confidence(0.93)
                        .outcomeSummary("Planner tactic completed the task without retries")
                        .evidenceRefs(List.of(VerdictEvidenceRef.builder()
                                .traceId("trace-derive")
                                .spanId("skill-1")
                                .outputFragment("Planner tactic completed the task without retries")
                                .build()))
                        .build());

        assertNotNull(proposal);
        assertEquals("medium", proposal.getRiskLevel());
        assertTrue(proposal.getSummary().contains("Planner tactic"));
        assertTrue(proposal.getBehaviorInstructions().contains("Planner tactic"));
        assertTrue(proposal.getExpectedOutcome().contains("Planner tactic"));
    }

    @Test
    void shouldBuildRunSpecificSuccessfulProposalInsteadOfPlannerTemplate() {
        EvolutionProposal proposal = llmEvolutionService.propose(
                RunRecord.builder()
                        .id("run-success-specific")
                        .golemId("golem-1")
                        .build(),
                RunVerdict.builder()
                        .outcomeStatus("COMPLETED")
                        .processStatus("CLEAN")
                        .confidence(0.97)
                        .outcomeSummary("Deployment verification sequence completed without retries")
                        .evidenceRefs(List.of(VerdictEvidenceRef.builder()
                                .traceId("trace-success")
                                .spanId("skill-2")
                                .outputFragment("Deployment verification sequence completed without retries")
                                .build()))
                        .build());

        assertNotNull(proposal);
        assertTrue(proposal.getSummary().contains("Deployment verification"));
        assertTrue(proposal.getBehaviorInstructions().contains("Deployment verification"));
        assertTrue(proposal.getProposedPatch().contains("Deployment verification"));
        assertFalse(proposal.getSummary().toLowerCase().contains("planner"));
    }
}
