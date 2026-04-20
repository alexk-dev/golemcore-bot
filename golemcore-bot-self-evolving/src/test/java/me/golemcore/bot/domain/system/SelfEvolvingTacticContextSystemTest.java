package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.selfevolving.tactic.TacticSearchService;
import me.golemcore.bot.domain.selfevolving.tactic.TacticTurnContextService;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelfEvolvingTacticContextSystemTest {

    private SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private TacticSearchService tacticSearchService;
    private SelfEvolvingTacticContextSystem system;

    @BeforeEach
    void setUp() {
        runtimeConfigPort = mock(SelfEvolvingRuntimeConfigPort.class);
        tacticSearchService = mock(TacticSearchService.class);
        TacticTurnContextService tacticTurnContextService = new TacticTurnContextService(
                runtimeConfigPort,
                tacticSearchService,
                null);
        system = new SelfEvolvingTacticContextSystem(runtimeConfigPort, tacticTurnContextService);
    }

    @Test
    void shouldAttachTacticQueryContextAsTransientAdvisoryMessageWithoutMutatingSystemPrompt() {
        AgentContext context = userContext("session-1", "chat-1");
        context.setSystemPrompt("Base prompt");
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticAdvisoryCount()).thenReturn(1);
        TacticSearchQuery query = query();
        when(tacticSearchService.buildQuery(context)).thenReturn(query);
        when(tacticSearchService.search(query)).thenReturn(List.of(TacticSearchResult.builder()
                .tacticId("planner")
                .title("Planner tactic")
                .behaviorSummary("Recover from failed shell commands with a minimal ordered plan.")
                .toolSummary("shell")
                .promotionState("approved")
                .build()));

        AgentContext result = system.process(context);

        assertNotNull(result.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY));
        assertNotNull(result.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_RESULTS));
        assertNull(result.getActiveSkill());
        assertEquals("Base prompt", result.getSystemPrompt());
        assertEquals(2, result.getMessages().size());
        Message advisory = result.getMessages().getFirst();
        Message userTurn = result.getMessages().get(1);
        assertEquals("assistant", advisory.getRole());
        assertEquals("recover from failed shell command", userTurn.getContent());
        assertTrue(advisory.getContent().contains("Planner tactic"));
        assertTrue(advisory.getContent().contains("advisory"));
        assertNotNull(advisory.getMetadata());
        assertEquals(Boolean.TRUE, advisory.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL));
        assertEquals(ContextAttributes.MESSAGE_INTERNAL_KIND_TACTIC_ADVISORY,
                advisory.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL_KIND));
        assertFalse(result.getSession().getMessages().stream().anyMatch(message -> {
            Map<String, Object> metadata = message.getMetadata() != null ? message.getMetadata()
                    : new LinkedHashMap<>();
            return Boolean.TRUE.equals(metadata.get(ContextAttributes.MESSAGE_INTERNAL));
        }));
    }

    @Test
    void shouldPreferSemanticIntentSummaryAndSuppressPlaceholderBehaviorInAdvisory() {
        AgentContext context = userContext("session-2", "chat-2");
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticAdvisoryCount()).thenReturn(1);
        TacticSearchQuery query = query();
        when(tacticSearchService.buildQuery(context)).thenReturn(query);
        when(tacticSearchService.search(query)).thenReturn(List.of(TacticSearchResult.builder()
                .tacticId("planner")
                .title("Planner tactic")
                .intentSummary("Recover from shell failures by checking tool availability first.")
                .behaviorSummary("selfevolving:fix:tool_policy")
                .toolSummary("Use `command -v` before invoking shell tools.")
                .outcomeSummary("Reduce repeated missing binary failures.")
                .promotionState("approved")
                .build()));

        AgentContext result = system.process(context);
        Message advisory = result.getMessages().getFirst();

        assertTrue(advisory.getContent().contains("Recover from shell failures by checking tool availability first."));
        assertTrue(advisory.getContent().contains("Use `command -v` before invoking shell tools."));
        assertFalse(advisory.getContent().contains("selfevolving:fix:tool_policy"));
    }

    @Test
    void shouldRecordAppliedTacticIdsWhenSearchReturnsResults() {
        AgentContext context = userContext("session-applied", "chat-applied");
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticAdvisoryCount()).thenReturn(1);
        TacticSearchQuery query = query();
        when(tacticSearchService.buildQuery(context)).thenReturn(query);
        when(tacticSearchService.search(query)).thenReturn(List.of(
                TacticSearchResult.builder().tacticId("tactic-alpha").title("Alpha").promotionState("approved").build(),
                TacticSearchResult.builder().tacticId("tactic-beta").title("Beta").promotionState("active").build()));

        AgentContext result = system.process(context);

        List<String> appliedIds = result.getAttribute(ContextAttributes.APPLIED_TACTIC_IDS);
        assertNotNull(appliedIds);
        assertEquals(List.of("tactic-alpha"), appliedIds);
    }

    @Test
    void shouldClearStaleTacticSelectionGuidanceAndAdvisoryWhenSearchReturnsNoResults() {
        Map<String, Object> advisoryMetadata = new LinkedHashMap<>();
        advisoryMetadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        advisoryMetadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND,
                ContextAttributes.MESSAGE_INTERNAL_KIND_TACTIC_ADVISORY);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-empty").chatId("chat-empty").messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("assistant").content("stale tactic advisory").metadata(advisoryMetadata)
                                .build(),
                        Message.builder().role("user").content("recover from failed shell command").build())))
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION,
                TacticSearchResult.builder().tacticId("stale-selection").build());
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_GUIDANCE,
                TacticSearchResult.builder().tacticId("stale-guidance").build());
        context.setAttribute(ContextAttributes.APPLIED_TACTIC_IDS, List.of("stale-selection"));
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticAdvisoryCount()).thenReturn(1);
        TacticSearchQuery query = query();
        when(tacticSearchService.buildQuery(context)).thenReturn(query);
        when(tacticSearchService.search(query)).thenReturn(List.of());

        AgentContext result = system.process(context);

        assertEquals(query, result.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY));
        assertEquals(List.of(), result.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_RESULTS));
        assertNull(result.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION));
        assertNull(result.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_GUIDANCE));
        assertNull(result.getAttribute(ContextAttributes.APPLIED_TACTIC_IDS));
        assertEquals(1, result.getMessages().size());
        assertEquals("recover from failed shell command", result.getMessages().getFirst().getContent());
    }

    @Test
    void shouldSkipWhenSelfEvolvingIsDisabled() {
        AgentContext context = userContext("session-disabled", "chat-disabled");
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(false);

        AgentContext result = system.process(context);

        assertNull(result.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY));
        assertEquals(1, result.getMessages().size());
    }

    private AgentContext userContext(String sessionId, String chatId) {
        return AgentContext.builder()
                .session(AgentSession.builder()
                        .id(sessionId)
                        .chatId(chatId)
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>(List.of(Message.builder()
                        .role("user")
                        .content("recover from failed shell command")
                        .build())))
                .build();
    }

    private TacticSearchQuery query() {
        return TacticSearchQuery.builder()
                .rawQuery("recover from failed shell command")
                .queryViews(List.of("recover", "shell", "failure"))
                .build();
    }
}
