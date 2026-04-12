package me.golemcore.bot.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.model.hive.HiveCardDetail;
import me.golemcore.bot.domain.model.hive.HiveCardSummary;
import me.golemcore.bot.domain.model.hive.HiveThreadMessage;
import me.golemcore.bot.domain.service.HiveSdlcService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveSdlcToolsTest {

    private HiveSdlcService hiveSdlcService;
    private RuntimeConfigService runtimeConfigService;

    @BeforeEach
    void setUp() {
        hiveSdlcService = mock(HiveSdlcService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isHiveSdlcCurrentContextEnabled()).thenReturn(true);
        when(runtimeConfigService.isHiveSdlcCardReadEnabled()).thenReturn(true);
        when(runtimeConfigService.isHiveSdlcCardSearchEnabled()).thenReturn(true);
        when(runtimeConfigService.isHiveSdlcThreadMessageEnabled()).thenReturn(true);
        when(runtimeConfigService.isHiveSdlcReviewRequestEnabled()).thenReturn(true);
        when(runtimeConfigService.isHiveSdlcFollowupCardCreateEnabled()).thenReturn(true);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .channelType("hive")
                        .chatId("thread-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.HIVE_THREAD_ID, "thread-1");
        context.setAttribute(ContextAttributes.HIVE_CARD_ID, "card-1");
        context.setAttribute(ContextAttributes.HIVE_COMMAND_ID, "cmd-1");
        context.setAttribute(ContextAttributes.HIVE_RUN_ID, "run-1");
        context.setAttribute(ContextAttributes.HIVE_GOLEM_ID, "golem-1");
        AgentContextHolder.set(context);
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    @Test
    void shouldReturnCurrentHiveContext() {
        HiveCurrentContextTool tool = new HiveCurrentContextTool(runtimeConfigService);

        ToolResult result = tool.execute(Map.of()).join();

        assertTrue(result.isSuccess());
        Map<?, ?> data = (Map<?, ?>) result.getData();
        assertEquals("thread-1", data.get("threadId"));
        assertEquals("card-1", data.get("cardId"));
        assertEquals("cmd-1", data.get("commandId"));
        assertEquals("run-1", data.get("runId"));
        assertEquals("golem-1", data.get("golemId"));
    }

    @Test
    void shouldGetCurrentCardWhenCardIdIsOmitted() {
        HiveGetCardTool tool = new HiveGetCardTool(hiveSdlcService, runtimeConfigService);
        when(hiveSdlcService.getCard("card-1")).thenReturn(card("card-1"));

        ToolResult result = tool.execute(Map.of()).join();

        assertTrue(result.isSuccess());
        assertEquals("card-1", ((HiveCardDetail) result.getData()).id());
    }

    @Test
    void shouldSearchCardsWithFilters() {
        HiveSearchCardsTool tool = new HiveSearchCardsTool(hiveSdlcService, runtimeConfigService);
        when(hiveSdlcService.searchCards(argThat(request -> "board-1".equals(request.boardId())
                && "task".equals(request.kind())))).thenReturn(List.of(cardSummary("card-1")));

        ToolResult result = tool.execute(Map.of("board_id", "board-1", "kind", "task")).join();

        assertTrue(result.isSuccess());
        verify(hiveSdlcService).searchCards(argThat(request -> "board-1".equals(request.boardId())
                && "task".equals(request.kind())));
    }

    @Test
    void shouldPostThreadMessageToCurrentThread() {
        HivePostThreadMessageTool tool = new HivePostThreadMessageTool(hiveSdlcService, runtimeConfigService);
        when(hiveSdlcService.postThreadMessage("thread-1", "Done"))
                .thenReturn(new HiveThreadMessage("msg-1", "thread-1", "card-1", null, null, null, "NOTE",
                        "OPERATOR", "golem-1", "Bot", "Done", Instant.parse("2026-03-18T00:00:00Z")));

        ToolResult result = tool.execute(Map.of("body", "Done")).join();

        assertTrue(result.isSuccess());
        verify(hiveSdlcService).postThreadMessage("thread-1", "Done");
    }

    @Test
    void shouldRequestReviewForCurrentCard() {
        HiveRequestReviewTool tool = new HiveRequestReviewTool(hiveSdlcService, runtimeConfigService);
        when(hiveSdlcService.requestReview(argThat(cardId -> "card-1".equals(cardId)),
                argThat(request -> request.requiredReviewCount() == 1))).thenReturn(card("card-1"));

        ToolResult result = tool.execute(Map.of("required_review_count", 1)).join();

        assertTrue(result.isSuccess());
        verify(hiveSdlcService).requestReview(argThat(cardId -> "card-1".equals(cardId)),
                argThat(request -> request.requiredReviewCount() == 1));
    }

    @Test
    void shouldCreateFollowupCardWithInheritedParent() {
        HiveCreateFollowupCardTool tool = new HiveCreateFollowupCardTool(hiveSdlcService, runtimeConfigService);
        when(hiveSdlcService.createCard(argThat(request -> "card-1".equals(request.parentCardId())
                && "Follow-up".equals(request.title())))).thenReturn(card("card-2"));

        ToolResult result = tool.execute(Map.of(
                "title", "Follow-up",
                "prompt", "Check the follow-up",
                "inherit_current_card", true)).join();

        assertTrue(result.isSuccess());
        verify(hiveSdlcService).createCard(argThat(request -> "card-1".equals(request.parentCardId())
                && "Follow-up".equals(request.title())));
    }

    @Test
    void shouldDenySdlcToolsOutsideHiveSession() {
        AgentContextHolder.set(AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .build());
        HiveGetCardTool tool = new HiveGetCardTool(hiveSdlcService, runtimeConfigService);

        ToolResult result = tool.execute(Map.of("card_id", "card-1")).join();

        assertFalse(result.isSuccess());
        assertEquals(ToolFailureKind.POLICY_DENIED, result.getFailureKind());
    }

    private HiveCardDetail card(String id) {
        return new HiveCardDetail(id, "service-1", "board-1", "task", null, null, List.of(), null, List.of(), null,
                0, null, null, null, "thread-1", "Title", "Description", "Prompt", "ready", null, null,
                0, false, null, Instant.parse("2026-03-18T00:00:00Z"),
                Instant.parse("2026-03-18T00:00:00Z"), null);
    }

    private HiveCardSummary cardSummary(String id) {
        return new HiveCardSummary(id, "service-1", "board-1", "task", null, null, List.of(), null, List.of(), null,
                0, null, null, null, "thread-1", "Title", "ready", null, null, 0, false);
    }
}
