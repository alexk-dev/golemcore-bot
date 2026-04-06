package me.golemcore.bot.domain.selfevolving.candidate;

import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvolutionGateServiceTest {

    private EvolutionGateService gateService;

    @BeforeEach
    void setUp() {
        gateService = new EvolutionGateService();
    }

    @Test
    void shouldRejectWhenVerdictIsNull() {
        RunRecord run = RunRecord.builder().id("run-1").build();
        EvolutionProposal proposal = EvolutionProposal.builder().summary("some summary").build();

        assertFalse(gateService.shouldEvolve(run, null, proposal));
    }

    @Test
    void shouldRejectWhenProposalIsNull() {
        RunRecord run = RunRecord.builder().id("run-1").build();
        RunVerdict verdict = RunVerdict.builder()
                .confidence(0.8)
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().spanId("span-1").build()))
                .build();

        assertFalse(gateService.shouldEvolve(run, verdict, null));
    }

    @Test
    void shouldRejectWhenProposalIsEmpty() {
        RunRecord run = RunRecord.builder().id("run-1").build();
        RunVerdict verdict = RunVerdict.builder()
                .confidence(0.8)
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().spanId("span-1").build()))
                .build();
        EvolutionProposal proposal = EvolutionProposal.builder().build();

        assertFalse(gateService.shouldEvolve(run, verdict, proposal));
    }

    @Test
    void shouldRejectWhenConfidenceBelowThreshold() {
        RunRecord run = RunRecord.builder().id("run-1").build();
        RunVerdict verdict = RunVerdict.builder()
                .confidence(0.3)
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().spanId("span-1").build()))
                .build();
        EvolutionProposal proposal = EvolutionProposal.builder().summary("some summary").build();

        assertFalse(gateService.shouldEvolve(run, verdict, proposal));
    }

    @Test
    void shouldRejectWhenNoEvidenceRefs() {
        RunRecord run = RunRecord.builder().id("run-1").build();
        RunVerdict verdict = RunVerdict.builder()
                .confidence(0.8)
                .evidenceRefs(List.of())
                .build();
        EvolutionProposal proposal = EvolutionProposal.builder().summary("some summary").build();

        assertFalse(gateService.shouldEvolve(run, verdict, proposal));
    }

    @Test
    void shouldRejectWhenEvidenceRefsAreAllEmpty() {
        RunRecord run = RunRecord.builder().id("run-1").build();
        RunVerdict verdict = RunVerdict.builder()
                .confidence(0.8)
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().build()))
                .build();
        EvolutionProposal proposal = EvolutionProposal.builder().summary("some summary").build();

        assertFalse(gateService.shouldEvolve(run, verdict, proposal));
    }

    @Test
    void shouldAcceptWhenAllCriteriaMet() {
        RunRecord run = RunRecord.builder().id("run-1").build();
        RunVerdict verdict = RunVerdict.builder()
                .confidence(0.8)
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().spanId("span-1").build()))
                .build();
        EvolutionProposal proposal = EvolutionProposal.builder().summary("some summary").build();

        assertTrue(gateService.shouldEvolve(run, verdict, proposal));
    }

    @Test
    void shouldAcceptAtExactConfidenceThreshold() {
        RunRecord run = RunRecord.builder().id("run-1").build();
        RunVerdict verdict = RunVerdict.builder()
                .confidence(0.5)
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().metricName("latency").build()))
                .build();
        EvolutionProposal proposal = EvolutionProposal.builder().behaviorInstructions("do something").build();

        assertTrue(gateService.shouldEvolve(run, verdict, proposal));
    }
}
