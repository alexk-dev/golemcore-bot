package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.context.ContextAssembler;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SelfEvolvingRunService;
import me.golemcore.bot.domain.service.TacticSearchService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextBuildingSystemTacticQueryTest {

    @Test
    void shouldAttachTacticQueryContextWithoutMutatingCuratedSkillSelection() {
        ContextAssembler assembler = mock(ContextAssembler.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        SelfEvolvingRunService selfEvolvingRunService = mock(SelfEvolvingRunService.class);
        TacticSearchService tacticSearchService = mock(TacticSearchService.class);
        ContextBuildingSystem system = new ContextBuildingSystem(
                assembler,
                runtimeConfigService,
                selfEvolvingRunService,
                tacticSearchService);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("session-1")
                        .chatId("chat-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>(List.of(Message.builder()
                        .role("user")
                        .content("recover from failed shell command")
                        .build())))
                .build();

        when(assembler.assemble(context)).thenReturn(context);
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(true)
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder().enabled(true).build())
                .build());
        when(selfEvolvingRunService.startRun(context)).thenReturn(RunRecord.builder()
                .id("run-1")
                .artifactBundleId("bundle-1")
                .build());
        when(tacticSearchService.buildQuery(context)).thenReturn(TacticSearchQuery.builder()
                .rawQuery("recover from failed shell command")
                .queryViews(List.of("recover", "shell", "failure"))
                .build());
        when(tacticSearchService.search(context)).thenReturn(List.of(TacticSearchResult.builder()
                .tacticId("planner")
                .promotionState("approved")
                .build()));

        AgentContext result = system.process(context);

        assertNotNull(result.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY));
        assertNotNull(result.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_RESULTS));
        assertNull(result.getActiveSkill());
    }
}
