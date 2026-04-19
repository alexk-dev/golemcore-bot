package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.selfevolving.tactic.TacticOutcomeJournalService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void shouldNotWriteTacticOutcomeJournalDirectly() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("chat-1").build())
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION,
                TacticSearchResult.builder().tacticId("tactic-1").build());
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .build());

        AgentContext result = system.process(context);

        assertEquals(context, result);
        verify(tacticOutcomeJournalService, never()).record(org.mockito.ArgumentMatchers.any());
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
    void shouldReturnContextWhenSelectionPresent() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("chat-1").build())
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION,
                TacticSearchResult.builder().tacticId("tactic-1").build());
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .build());

        AgentContext result = system.process(context);

        assertEquals(context, result);
        verify(tacticOutcomeJournalService, never()).record(org.mockito.ArgumentMatchers.any());
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
