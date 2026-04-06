package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticOutcomeEntry;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.selfevolving.tactic.TacticOutcomeJournalService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class TacticOutcomeFeedbackSystemTest {

    private RuntimeConfigService runtimeConfigService;
    private TacticOutcomeJournalService tacticOutcomeJournalService;
    private Clock clock;
    private TacticOutcomeFeedbackSystem system;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        tacticOutcomeJournalService = mock(TacticOutcomeJournalService.class);
        clock = Clock.fixed(Instant.parse("2026-04-05T12:00:00Z"), ZoneOffset.UTC);
        system = new TacticOutcomeFeedbackSystem(runtimeConfigService, tacticOutcomeJournalService, clock);
    }

    @Test
    void shouldRecordTacticOutcomeWithAllFieldsPopulated() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("chat-1").build())
                .build();
        TacticSearchResult selection = TacticSearchResult.builder()
                .tacticId("tactic-1")
                .explanation(TacticSearchExplanation.builder()
                        .searchMode("hybrid")
                        .finalScore(0.85d)
                        .build())
                .build();
        TacticSearchQuery query = TacticSearchQuery.builder()
                .rawQuery("how to deploy")
                .queryViews(List.of("deploy", "deployment"))
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION, selection);
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY, query);
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .build());

        system.process(context);

        ArgumentCaptor<TacticOutcomeEntry> captor = ArgumentCaptor.forClass(TacticOutcomeEntry.class);
        verify(tacticOutcomeJournalService).record(captor.capture());
        TacticOutcomeEntry entry = captor.getValue();
        assertEquals("tactic-1", entry.getTacticId());
        assertEquals("how to deploy", entry.getRawQuery());
        assertEquals(List.of("deploy", "deployment"), entry.getQueryViews());
        assertEquals("hybrid", entry.getSearchMode());
        assertEquals(0.85d, entry.getFinalScore());
        assertEquals("success", entry.getFinishReason());
        assertEquals(Instant.parse("2026-04-05T12:00:00Z"), entry.getRecordedAt());
    }

    @Test
    void shouldRecordErrorFinishReason() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("chat-1").build())
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION,
                TacticSearchResult.builder().tacticId("tactic-2").build());
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.ERROR)
                .build());

        system.process(context);

        ArgumentCaptor<TacticOutcomeEntry> captor = ArgumentCaptor.forClass(TacticOutcomeEntry.class);
        verify(tacticOutcomeJournalService).record(captor.capture());
        assertEquals("error", captor.getValue().getFinishReason());
    }

    @Test
    void shouldNotProcessWhenNoTacticSelection() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("chat-1").build())
                .build();
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .build());

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenNoTurnOutcome() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("chat-1").build())
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION,
                TacticSearchResult.builder().tacticId("tactic-1").build());

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenBothTacticSelectionAndTurnOutcomePresent() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("chat-1").build())
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION,
                TacticSearchResult.builder().tacticId("tactic-1").build());
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .build());

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldNotBreakPipelineOnRecordFailure() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("chat-1").build())
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION,
                TacticSearchResult.builder().tacticId("tactic-1").build());
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .build());
        doThrow(new RuntimeException("storage failure"))
                .when(tacticOutcomeJournalService).record(any());

        AgentContext result = system.process(context);

        assertEquals(context, result);
    }

    @Test
    void shouldBeDisabledWhenSelfEvolvingIsDisabled() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(false);

        assertFalse(system.isEnabled());
    }

    @Test
    void shouldBeEnabledWhenSelfEvolvingIsEnabled() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);

        assertTrue(system.isEnabled());
    }
}
